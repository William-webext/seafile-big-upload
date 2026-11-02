package dev.seafilebigupload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Il caricamento in sottofondo (il nome della classe resta "VideoWorker" perché WorkManager lo ha salvato nei lavori programmati,
 * anche se ora carica anche le foto): parte ogni tanto, quando compare un file nuovo e quando premi "Carica ora".
 */
class VideoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val p = Prefs(applicationContext)
        val origine = when {
            inputData.getBoolean(Pianifica.CHIAVE_TRIGGER, false) -> "file nuovo"
            inputData.getBoolean(Pianifica.CHIAVE_RIPROVA, false) -> "controllo programmato"
            tags.contains("periodico") -> "periodico"
            else -> "manuale"
        }
        Log.i("BigUpload", "giro avviato ($origine)")
        try {
            if (!p.configurato) return@withContext Result.success()
            // se un altro giro sta già girando questo non deve fare niente: né toccare avanzamento e notifica del primo, né fermarlo
            if (Caricatore.occupato()) {
                Log.i("BigUpload", "giro non avviato: ce n'è già uno in corso")
                return@withContext Result.success()
            }
            val db = Db(applicationContext)
            try {
                // prima si guarda, senza rete e senza notifiche, se c'è davvero qualcosa da fare
                val piano = Caricatore.piano(applicationContext, p, db)
                if (p.riconciliato && piano.daFare.isEmpty()) {
                    p.ultimoControlloMs = System.currentTimeMillis()
                    Log.i("BigUpload", "giro a vuoto: niente da caricare (${piano.inAttesa} in attesa)")
                    programmaSeServe(p, piano.prossimoMs)
                    return@withContext Result.success()
                }

                val nm = applicationContext.getSystemService(NotificationManager::class.java)
                creaCanali(nm)
                try {
                    setForeground(infoProgresso(T.s(R.string.notif_checking)))
                } catch (_: Exception) {
                    // se il sistema non permette il servizio in primo piano si prosegue lo stesso
                }
                p.interrompi = false
                p.progFase = T.s(R.string.phase_checking)   // la barra compare subito, anche prima del primo file
                var possiede = false   // solo chi ha davvero fatto il giro ripulisce avanzamento e notifica
                try {
                    var ultimaNotifica = 0L
                    possiede = true
                    val e = Caricatore.esegui(applicationContext, p, db, { isStopped || p.interrompi }, { msg ->
                        p.ultimoEsito = "${ora()}  $msg"
                    }) { nome, idx, n, fatti, tot ->
                        p.progFase = ""
                        p.progNome = nome; p.progIdx = idx; p.progN = n; p.progFatti = fatti; p.progTot = tot
                        val adesso = System.currentTimeMillis()
                        if (adesso - ultimaNotifica > 1500 || fatti == tot) {
                            ultimaNotifica = adesso
                            nm.notify(ID_PROGRESSO, notificaProgresso("($idx/$n) $nome", tot, fatti))
                        }
                    }
                    Log.i("BigUpload", "giro finito: ${e?.caricati?.size} caricati, ${e?.falliti?.size} falliti, ${e?.inAttesa} in attesa")
                    if (e == null) { possiede = false; return@withContext Result.success() }   // un altro giro è già in corso
                    val note = when {
                        e.caricati.isEmpty() -> ""
                        e.dateConservate == e.caricati.size -> T.s(R.string.note_dates_all)
                        e.dateConservate == 0 -> T.s(R.string.note_dates_none)
                        else -> T.s(R.string.note_dates_some, e.dateConservate, e.caricati.size)
                    }
                    val testo = buildString {
                        append(T.s(R.string.result_summary, e.caricati.size, e.falliti.size, e.giaSulServer, e.inAttesa))
                        if (note.isNotEmpty()) append("\n$note")
                        e.caricati.take(6).forEach { append("\n").append(T.s(R.string.line_uploaded, it)) }
                        e.falliti.take(6).forEach { append("\n").append(T.s(R.string.line_error, it)) }
                    }
                    p.ultimoEsito = "${ora()}\n$testo"
                    p.ultimoGiroMs = System.currentTimeMillis()
                    p.ultimoControlloMs = p.ultimoGiroMs
                    p.ultimoCaricati = e.caricati.size
                    p.ultimoFalliti = e.falliti.size
                    p.ultimoNote = note
                    if (!e.autenticazione) programmaSeServe(p, e.prossimoMs)
                    if (e.autenticazione) nm.notify(ID_ESITO, notificaEsito(T.s(R.string.notif_auth_title), T.s(R.string.notif_auth_text, testo)))
                    else if (e.falliti.isNotEmpty()) nm.notify(ID_ESITO, notificaEsito(T.s(R.string.notif_failed_title, e.falliti.size), testo))
                    else if (e.caricati.isNotEmpty() && p.notifiche) nm.notify(ID_ESITO, notificaEsito(T.s(R.string.notif_done_title, e.caricati.size), testo))
                } catch (ex: Exception) {
                    val msg = T.s(R.string.notif_crash_text, ex.message ?: ex.javaClass.simpleName)
                    p.ultimoEsito = "${ora()}\n$msg"
                    p.ultimoGiroMs = System.currentTimeMillis()
                    p.ultimoCaricati = 0
                    p.ultimoFalliti = 1
                    p.ultimoNote = msg
                    nm.notify(ID_ESITO, notificaEsito(T.s(R.string.notif_crash_title), msg))
                } finally {
                    if (possiede) {
                        p.azzeraProgresso()
                        nm.cancel(ID_PROGRESSO)
                    }
                }
            } finally {
                db.close()
            }
            Result.success()
        } finally {
            // il lavoro "al file nuovo" si riarma da solo: WorkManager lo fa partire una sola volta per ogni gruppo di modifiche
            // ... ma solo se il caricamento automatico è ancora acceso (se l'utente l'ha spento mentre girava, non si riarma)
            if (inputData.getBoolean(Pianifica.CHIAVE_TRIGGER, false) && p.automatico) Pianifica.armaTrigger(applicationContext, p, dopoQuestoGiro = true)
        }
    }

    /** Se resta qualcosa che diventerà caricabile più tardi (file appena registrato, errore da ripetere) si programma un secondo controllo. */
    private fun programmaSeServe(p: Prefs, prossimoMs: Long?) {
        if (p.automatico && prossimoMs != null) {
            Pianifica.riprovaTra(applicationContext, p, prossimoMs - System.currentTimeMillis() + 5_000L, inputData.getBoolean(Pianifica.CHIAVE_RIPROVA, false))
        }
    }

    private fun ora() = SimpleDateFormat("dd/MM/yyyy HH:mm", T.locale()).format(Date())

    private fun creaCanali(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(CANALE_PROGRESSO, T.s(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANALE_ESITO, T.s(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun apriApp() = PendingIntent.getActivity(
        applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificaProgresso(testo: String, tot: Long = 0, fatti: Long = 0) = NotificationCompat.Builder(applicationContext, CANALE_PROGRESSO)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Seafile Big Upload")
        .setContentText(testo)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(apriApp())
        .apply { if (tot > 0) setProgress(100, (100 * fatti / tot).toInt(), false) }
        .build()

    private fun infoProgresso(testo: String): ForegroundInfo {
        val n = notificaProgresso(testo)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID_PROGRESSO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ID_PROGRESSO, n)
    }

    private fun notificaEsito(titolo: String, testo: String) = NotificationCompat.Builder(applicationContext, CANALE_ESITO)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle(titolo)
        .setContentText(testo.lineSequence().first())
        .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
        .setAutoCancel(true)
        .setContentIntent(apriApp())
        .build()

    companion object {
        const val CANALE_PROGRESSO = "progresso"
        const val CANALE_ESITO = "esito"
        const val ID_PROGRESSO = 1
        const val ID_ESITO = 2
    }
}
