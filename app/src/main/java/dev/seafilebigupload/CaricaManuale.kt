package dev.seafilebigupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Il lavoro deciso dall'utente con "Carica file o cartella": dove va e cosa caricare (con i nomi già risolti), più i permessi di lettura da rilasciare alla fine. */
class LavoroManuale(val repoId: String, val base: String, val azioni: List<Azione>, val permessi: List<String>)

/**
 * Il lavoro manuale sta in un file della memoria privata dell'app (non nelle impostazioni: può avere migliaia di file) e i file già caricati
 * si segnano uno per riga in un secondo file: se il sistema ferma il lavoro e lo riprende, riparte da dove era arrivato.
 */
object Manuale {
    const val LAVORO = "carica-manuale"
    private fun lavoro(ctx: Context) = File(ctx.filesDir, "manuale.json")
    private fun fatti(ctx: Context) = File(ctx.filesDir, "manuale-fatti.txt")

    /** Un lavoro manuale è già in coda o in corso? Chiamare fuori dal thread principale. */
    fun inCorso(ctx: Context): Boolean = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(LAVORO).get().any { !it.state.isFinished }

    fun salva(ctx: Context, j: LavoroManuale) {
        rilascia(ctx)   // eventuali permessi rimasti da un lavoro precedente interrotto
        fatti(ctx).delete()
        val o = JSONObject().put("repoId", j.repoId).put("base", j.base)
            .put("permessi", JSONArray(j.permessi))
            .put("azioni", JSONArray().also { a ->
                for (x in j.azioni) a.put(JSONObject().put("uri", x.uri).put("dim", x.dimensione).put("mod", x.modificatoMs)
                    .put("dir", x.dir).put("nl", x.nomeLocale).put("nr", x.nomeRemoto).put("sov", x.sovrascrivi))
            })
        lavoro(ctx).writeText(o.toString())
    }

    fun carica(ctx: Context): LavoroManuale? {
        val f = lavoro(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("azioni")
            val permessi = o.getJSONArray("permessi")
            LavoroManuale(
                o.getString("repoId"), o.getString("base"),
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Azione(it.getString("uri"), it.getLong("dim"), it.getLong("mod"), it.getString("dir"), it.getString("nl"), it.getString("nr"), it.getBoolean("sov"))
                },
                (0 until permessi.length()).map { permessi.getString(it) },
            )
        } catch (e: Exception) {
            Log.w("BigUpload", "lavoro manuale illeggibile", e)
            null
        }
    }

    fun giaFatti(ctx: Context): Set<Int> = fatti(ctx).takeIf { it.exists() }?.readLines()?.mapNotNull { it.trim().toIntOrNull() }?.toHashSet() ?: emptySet()
    fun segnaFatto(ctx: Context, i: Int) = fatti(ctx).appendText("$i\n")

    /** Rilascia i permessi di lettura sui file scelti: non servono più. */
    fun rilascia(ctx: Context) {
        val j = carica(ctx) ?: return
        for (u in j.permessi) {
            try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { /* non era stato preso */ }
        }
    }

    fun pulisci(ctx: Context) {
        rilascia(ctx)
        lavoro(ctx).delete(); fatti(ctx).delete()
    }
}

/** Carica in sottofondo i file scelti a mano, uno dopo l'altro, con notifica di avanzamento e pulsante per fermare. Dopo ogni file chiede al server dimensione e controlla. */
class CaricaManualeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val p = Prefs(ctx)
        val job = Manuale.carica(ctx) ?: return@withContext Result.success()
        if (p.url.isEmpty() || p.token.isEmpty()) { Manuale.pulisci(ctx); return@withContext Result.success() }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(VideoWorker.CANALE_PROGRESSO, T.s(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(VideoWorker.CANALE_ESITO, T.s(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT))
        try { setForeground(info(T.s(R.string.manual_starting))) } catch (_: Exception) { /* si prosegue lo stesso */ }

        val client = SeafileClient(p.url, p.token)
        val fatti = Manuale.giaFatti(ctx).toMutableSet()
        val falliti = ArrayList<String>()
        var auth = false
        var ultimaNotifica = 0L
        for ((i, a) in job.azioni.withIndex()) {
            if (isStopped) break
            if (i in fatti) continue
            val testo = T.s(R.string.manual_progress, i + 1, job.azioni.size, a.nomeRemoto)
            nm.notify(ID_PROGRESSO, notifica(testo, a.dimensione, 0))
            try {
                client.assicuraCartella(job.repoId, job.base, a.dir)
                val link = client.uploadLink(job.repoId, a.dir)
                client.upload(link, a.dir, a.nomeRemoto, a.dimensione, a.modificatoMs,
                    { ctx.contentResolver.openInputStream(Uri.parse(a.uri)) ?: throw IOException(T.s(R.string.err_cannot_open)) },
                    { isStopped }, a.sovrascrivi) { f ->
                    val adesso = System.currentTimeMillis()
                    if (adesso - ultimaNotifica > 1500 || f == a.dimensione) { ultimaNotifica = adesso; nm.notify(ID_PROGRESSO, notifica(testo, a.dimensione, f)) }
                }
                val dopo = client.remoteDetail(job.repoId, "${a.dir.trimEnd('/')}/${a.nomeRemoto}")
                if (dopo == null || dopo.first != a.dimensione) {
                    throw IOException(T.s(R.string.err_final_check, dopo?.first?.toString() ?: T.s(R.string.err_no_file), a.dimensione))
                }
                Manuale.segnaFatto(ctx, i); fatti.add(i)
                Log.i("BigUpload", "caricato a mano e controllato ${a.dir}/${a.nomeRemoto}${if (a.sovrascrivi) " (sovrascritto)" else ""}")
            } catch (e: AuthException) {
                auth = true; falliti.add("${a.nomeRemoto}: ${e.message}"); break
            } catch (e: InterruptedUpload) {
                break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); break
            } catch (e: Exception) {
                Log.w("BigUpload", "fallito a mano ${a.nomeRemoto}", e)
                falliti.add("${a.nomeRemoto}: ${e.message}")
            }
        }
        nm.cancel(ID_PROGRESSO)
        val totale = job.azioni.size
        val caricati = fatti.size
        when {
            auth -> nm.notify(ID_ESITO, esito(T.s(R.string.notif_auth_title), T.s(R.string.notif_auth_text, falliti.joinToString("\n"))))
            isStopped -> nm.notify(ID_ESITO, esito(T.s(R.string.manual_stopped, caricati, totale), ""))
            falliti.isNotEmpty() -> nm.notify(ID_ESITO, esito(T.s(R.string.manual_result_fail, caricati, falliti.size), falliti.take(6).joinToString("\n")))
            else -> nm.notify(ID_ESITO, esito(T.s(R.string.manual_result_ok, caricati), ""))
        }
        // un lavoro fermato dal sistema (non dall'utente) riparte da solo e riprende dai file non ancora segnati; negli altri casi si chiude
        if (!isStopped) Manuale.pulisci(ctx)
        Result.success()
    }

    private fun apriApp() = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun notifica(testo: String, tot: Long, fatti: Long) = NotificationCompat.Builder(applicationContext, VideoWorker.CANALE_PROGRESSO)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Seafile Big Upload")
        .setContentText(testo)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(apriApp())
        .addAction(0, T.s(R.string.btn_stop), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        .apply { if (tot > 0) setProgress(100, (100 * fatti / tot).toInt(), false) }
        .build()

    private fun info(testo: String): ForegroundInfo {
        val n = notifica(testo, 0, 0)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID_PROGRESSO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(ID_PROGRESSO, n)
    }

    private fun esito(titolo: String, testo: String) = NotificationCompat.Builder(applicationContext, VideoWorker.CANALE_ESITO)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle(titolo)
        .apply { if (testo.isNotEmpty()) setContentText(testo.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(testo)) }
        .setAutoCancel(true).setContentIntent(apriApp())
        .build()

    private companion object {
        const val ID_PROGRESSO = 3
        const val ID_ESITO = 4
    }
}
