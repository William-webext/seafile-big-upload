package dev.seafilebigupload

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/** Il nome della libreria come si legge nell'elenco e nelle schermate: per quelle non proprie dice sempre di chi sono. */
fun etichettaLibreria(ctx: Context, l: Libreria): String = when (l.origine) {
    Origine.PROPRIA -> l.nome
    Origine.CONDIVISA -> ctx.getString(R.string.library_item_shared, l.nome, l.di)
    Origine.GRUPPO -> ctx.getString(R.string.library_item_group, l.nome, l.di)
}

/** Dove si carica: libreria e cartella (`dir` parte da "/"). `etichetta` è il nome della libreria con il proprietario, se non è propria. */
class Destinazione(val repoId: String, val etichetta: String, val dir: String)

/**
 * Scelta guidata della destinazione sul server: prima la libreria (con di chi è; una non propria chiede una conferma esplicita),
 * poi si sfoglia dentro, una cartella alla volta, finché si preme "Carica qui". Tutto con dialoghi, come la scelta della cartella delle impostazioni.
 */
class SceltaDestinazione(private val act: AppCompatActivity, private val p: Prefs, private val dopo: (Destinazione) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private fun vivo() = !act.isFinishing && !act.isDestroyed

    fun avvia() {
        thread {
            try {
                val repos = SeafileClient(p.url, p.token).repos()
                ui.post { if (vivo()) elencoLibrerie(repos) }
            } catch (e: Exception) {
                ui.post { if (vivo()) errore(act.getString(R.string.what_libraries), e) }
            }
        }
    }

    private fun errore(titolo: String, e: Exception) {
        MaterialAlertDialogBuilder(act).setTitle(act.getString(R.string.dest_failed, titolo)).setMessage(e.message)
            .setPositiveButton(act.getString(R.string.ok), null).show()
    }

    private fun elencoLibrerie(repos: List<Libreria>) {
        if (repos.isEmpty()) {
            MaterialAlertDialogBuilder(act).setTitle(act.getString(R.string.library_none_title)).setMessage(act.getString(R.string.library_none_msg))
                .setPositiveButton(act.getString(R.string.ok), null).show()
            return
        }
        // dialogo con elenco: niente setMessage (vedi la trappola dei dialoghi)
        MaterialAlertDialogBuilder(act).setTitle(act.getString(R.string.dest_lib_title))
            .setItems(repos.map { etichettaLibreria(act, it) }.toTypedArray()) { _, i ->
                if (repos[i].origine == Origine.PROPRIA) cartelle(repos, repos[i], "/") else confermaAltrui(repos, repos[i])
            }
            .setNegativeButton(act.getString(R.string.cancel), null).show()
    }

    private fun confermaAltrui(repos: List<Libreria>, l: Libreria) {
        val testo = if (l.origine == Origine.GRUPPO) act.getString(R.string.library_other_msg_group, l.nome, l.di)
            else act.getString(R.string.library_other_msg_shared, l.nome, l.di)
        MaterialAlertDialogBuilder(act).setTitle(act.getString(R.string.library_other_title)).setMessage(testo)
            .setPositiveButton(act.getString(R.string.library_other_yes)) { _, _ -> cartelle(repos, l, "/") }
            .setNegativeButton(act.getString(R.string.library_other_back)) { _, _ -> elencoLibrerie(repos) }
            .setCancelable(false).show()
    }

    private fun cartelle(repos: List<Libreria>, l: Libreria, dir: String) {
        thread {
            try {
                val sotto = SeafileClient(p.url, p.token).cartelle(l.id, dir)
                ui.post { if (vivo()) dialogoCartelle(repos, l, dir, sotto) }
            } catch (e: Exception) {
                ui.post { if (vivo()) errore(act.getString(R.string.folder_list_failed), e) }
            }
        }
    }

    private fun dialogoCartelle(repos: List<Libreria>, l: Libreria, dir: String, sotto: List<String>) {
        val voci = ArrayList<String>()
        voci.add(act.getString(R.string.dest_here))
        voci.add(if (dir == "/") act.getString(R.string.dest_other_libs) else act.getString(R.string.folder_parent))
        sotto.forEach { voci.add("›  $it") }
        val etichetta = etichettaLibreria(act, l)
        MaterialAlertDialogBuilder(act).setTitle("$etichetta: $dir")
            .setItems(voci.toTypedArray()) { _, i ->
                when (i) {
                    0 -> dopo(Destinazione(l.id, etichetta, dir))
                    1 -> if (dir == "/") elencoLibrerie(repos) else cartelle(repos, l, dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" })
                    else -> cartelle(repos, l, dir.trimEnd('/') + "/" + sotto[i - 2])
                }
            }
            .setNegativeButton(act.getString(R.string.cancel), null).show()
    }
}
