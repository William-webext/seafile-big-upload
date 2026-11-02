package dev.seafilebigupload

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * Il lavoro vero: registra i file nuovi (video e/o foto, secondo le impostazioni), al primo giro li confronta una volta con il server,
 * poi carica quelli "da caricare", nella cartella giusta (con le sottocartelle per anno o anno/mese, se scelte).
 * Un solo giro alla volta (il lavoro periodico e quello avviato da un file nuovo non devono sovrapporsi).
 */
object Caricatore {
    private const val TAG = "BigUpload"
    private val lucchetto = ReentrantLock()

    /** Cosa c'è da fare adesso, deciso senza usare la rete: serve per accendere il servizio in primo piano solo quando conviene. */
    class Piano(val daFare: List<MediaLocale>, val inAttesa: Int, val prossimoMs: Long?)

    /** `dateConservate`: quanti dei file caricati ora hanno sul server la stessa data di modifica che avevano sul telefono. */
    class Esito(
        val giaSulServer: Int, val caricati: List<String>, val falliti: List<String>, val inAttesa: Int,
        val autenticazione: Boolean, val dateConservate: Int,
        /** Quando qualcosa che oggi non si poteva caricare (troppo recente, o in attesa dopo un errore) diventa caricabile; null se niente. */
        val prossimoMs: Long?,
    )

    /** C'è già un giro in corso? Un secondo lavoro che parte nel frattempo non deve toccare avanzamento e notifica del primo. */
    fun occupato(): Boolean = lucchetto.isLocked

    fun mb(b: Long) = "%.1f MB".format(b / 1e6)

    /** Registra i file del telefono e decide cosa caricare adesso. Nessuna richiesta di rete. */
    fun piano(ctx: Context, p: Prefs, db: Db): Piano {
        val locali = Media.leggi(ctx, p.albumLista, p.conFoto, p.conVideo)
        val perChiave = locali.associateBy { it.chiave }
        db.registraNuovi(locali)
        db.segnaAssenti(perChiave.keys)
        val ora = System.currentTimeMillis()
        val righe = db.tutti().filter { it.stato == Db.Stato.DA_CARICARE }
        val daFare = righe.filter { it.prossimoMs <= ora }
            .mapNotNull { perChiave[it.chiave] }
            .filter { it.modificatoMs <= ora - Media.STABILE_MS }
            .sortedBy { it.dimensione }
        val prossimo = righe.mapNotNull { r -> perChiave[r.chiave]?.let { v -> maxOf(r.prossimoMs, v.modificatoMs + Media.STABILE_MS) } }
            .filter { it > ora }.minOrNull()
        return Piano(daFare, righe.size - daFare.size, prossimo)
    }

    /** Segna la fase corrente per la barra della schermata principale (`tot` = 0: barra senza percentuale). */
    private fun fase(p: Prefs, testo: String, fatti: Long = 0, tot: Long = 0) {
        p.progNome = ""; p.progFase = testo; p.progFatti = fatti; p.progTot = tot
    }

    /** Confronto una tantum: i file che sul server ci sono già (stesso nome e stessa dimensione, in qualunque sottocartella) non si ricaricano. */
    fun riconcilia(ctx: Context, p: Prefs, db: Db, log: (String) -> Unit): Int {
        val client = SeafileClient(p.url, p.token)
        log(T.s(R.string.log_listing))
        fase(p, T.s(R.string.phase_list))
        val remoti = client.remoteFiles(p.repoId, p.remoteDir)
        val presenti = remoti.filter { it.size >= 0 }.map { "${it.name}|${it.size}" }.toHashSet()
        fase(p, T.s(R.string.phase_register))
        val locali = Media.leggi(ctx, p.albumLista, p.conFoto, p.conVideo)
        db.registraNuovi(locali)
        val giaSulServer = locali.filter { it.chiave in presenti }.map { it.chiave }
        var n = 0
        for ((i, blocco) in giaSulServer.chunked(100).withIndex()) {
            n += db.segnaSulServer(blocco)
            val fatti = minOf((i + 1) * 100, giaSulServer.size)
            fase(p, T.s(R.string.phase_compare, fatti, giaSulServer.size), fatti.toLong(), giaSulServer.size.toLong())
        }
        p.riconciliato = true
        log(T.s(R.string.log_compared, locali.size, n))
        return n
    }

    /** `null` se un altro giro è già in corso. `avanza` riceve (nome, posizione, totale, byte fatti, byte totali) per la barra di avanzamento. */
    fun esegui(
        ctx: Context, p: Prefs, db: Db, fermato: () -> Boolean, log: (String) -> Unit,
        avanza: (String, Int, Int, Long, Long) -> Unit,
    ): Esito? {
        if (!lucchetto.tryLock()) return null
        try {
            val client = SeafileClient(p.url, p.token)
            var giaSulServer = 0
            if (!p.riconciliato) giaSulServer = riconcilia(ctx, p, db, log)
            p.progFase = ""

            val piano = piano(ctx, p, db)
            val caricati = ArrayList<String>()
            val falliti = ArrayList<String>()
            var auth = false
            var dateConservate = 0
            val cartelleOk = HashSet<String>()   // cartelle già controllate o create in questo giro
            for ((i, v) in piano.daFare.withIndex()) {
                if (fermato()) break
                avanza(v.nome, i + 1, piano.daFare.size, 0, v.dimensione)
                log(T.s(R.string.log_uploading, i + 1, piano.daFare.size, v.nome, mb(v.dimensione)))
                val dest = Media.cartella(p.remoteDir, p.raggruppa, v)
                val percorso = "$dest/${v.nome}"
                try {
                    val sulServer = client.remoteSize(p.repoId, percorso)
                    when {
                        sulServer == v.dimensione -> { db.imposta(v.chiave, Db.Stato.FATTO_SERVER, percorso); giaSulServer++; continue }
                        sulServer != null -> {
                            // stesso nome, dimensione diversa: non si sovrascrive mai, lo decide una persona
                            db.fallito(v.chiave, T.s(R.string.err_size_differs, v.nome, sulServer, v.dimensione), true)
                            falliti.add(T.s(R.string.err_size_differs_short, v.nome)); continue
                        }
                    }
                    if (cartelleOk.add(dest)) client.assicuraCartella(p.repoId, p.remoteDir, dest)
                    val link = client.uploadLink(p.repoId, dest)
                    client.upload(link, dest, v.nome, v.dimensione, v.modificatoMs, { ctx.contentResolver.openInputStream(v.uri) ?: throw IOException(T.s(R.string.err_cannot_open)) }, fermato) { fatti ->
                        avanza(v.nome, i + 1, piano.daFare.size, fatti, v.dimensione)
                    }
                    val dopo = client.remoteDetail(p.repoId, percorso)
                    if (dopo == null || dopo.first != v.dimensione) {
                        throw IOException(T.s(R.string.err_final_check, dopo?.first?.toString() ?: T.s(R.string.err_no_file), v.dimensione))
                    }
                    db.imposta(v.chiave, Db.Stato.FATTO, percorso)
                    caricati.add(v.nome)
                    if (kotlin.math.abs(dopo.second - v.modificatoMs / 1000) <= 2) dateConservate++
                    Log.i(TAG, "caricato e controllato $percorso (data sul server ${dopo.second}, sul telefono ${v.modificatoMs / 1000})")
                } catch (e: AuthException) {
                    auth = true
                    falliti.add("${v.nome}: ${e.message}")
                    break
                } catch (e: InterruptedUpload) {
                    break
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()   // il sistema ha fermato il lavoro: non è un errore del file
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "fallito ${v.nome}", e)
                    db.fallito(v.chiave, e.message ?: e.javaClass.simpleName, e is RifiutoException)
                    falliti.add("${v.nome}: ${e.message}")
                }
            }
            val dopoGiro = piano(ctx, p, db)
            return Esito(giaSulServer, caricati, falliti, dopoGiro.inAttesa, auth, dateConservate, dopoGiro.prossimoMs)
        } finally {
            lucchetto.unlock()
        }
    }
}
