package dev.seafilebigupload

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Quando gira il caricamento: a ogni video nuovo, ogni 3 ore come rete di sicurezza, e a richiesta. */
object Pianifica {
    const val CHIAVE_TRIGGER = "trigger"
    const val CHIAVE_RIPROVA = "riprova"
    const val LAVORO_TRIGGER = "video-nuovo"
    const val LAVORO_PERIODICO = "video-periodico"
    const val LAVORO_RIPROVA = "video-riprova"
    const val LAVORO_ORA = "video-ora"

    private fun rete(p: Prefs) = if (p.soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED

    private fun richiestaTrigger(p: Prefs): OneTimeWorkRequest {
        val vincoli = Constraints.Builder().setRequiredNetworkType(rete(p))
        for (uri in Media.osservati(p.conFoto, p.conVideo)) vincoli.addContentUriTrigger(uri, true)
        return OneTimeWorkRequestBuilder<VideoWorker>()
            .setInputData(Data.Builder().putBoolean(CHIAVE_TRIGGER, true).build())
            .setConstraints(vincoli.setTriggerContentUpdateDelay(2, TimeUnit.MINUTES).setTriggerContentMaxDelay(10, TimeUnit.MINUTES).build())
            .build()
    }

    /** Mette in attesa il lavoro che parte quando MediaStore segnala un video nuovo. */
    fun armaTrigger(ctx: Context, p: Prefs, dopoQuestoGiro: Boolean = false) {
        // dopo un giro scatenato dal trigger si accoda al giro in corso; altrimenti si sostituisce quello in attesa
        val politica = if (dopoQuestoGiro) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(ctx).enqueueUniqueWork(LAVORO_TRIGGER, politica, richiestaTrigger(p))
    }

    /**
     * Un secondo controllo tra `ms` millisecondi: serve quando dopo un giro resta qualcosa che diventerà caricabile più tardi
     * (un video appena registrato, o un tentativo da ripetere dopo un errore). Il giro che lo programma è esso stesso "video-riprova"?
     * Allora il nuovo lavoro si accoda a quello in corso; altrimenti, se ce n'è già uno in attesa, resta quello.
     */
    fun riprovaTra(ctx: Context, p: Prefs, ms: Long, dopoQuestoGiro: Boolean) {
        val rq = OneTimeWorkRequestBuilder<VideoWorker>()
            .setInitialDelay(ms.coerceIn(60_000L, 6 * 3_600_000L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putBoolean(CHIAVE_RIPROVA, true).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(rete(p)).build())
            .build()
        val politica = if (dopoQuestoGiro) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(ctx).enqueueUniqueWork(LAVORO_RIPROVA, politica, rq)
    }

    /**
     * Il controllo periodico è la vera rete di sicurezza: ogni tanto (a scelta: 15 minuti, 1 ora, 6 ore, 1 giorno, 1 settimana; 15 minuti è il minimo
     * di Android) guarda se ci sono file da caricare. Un giro senza niente da fare non fa nessuna richiesta di rete e non mostra notifiche, quindi costa
     * pochissimo. Il trigger sui file nuovi (content URI) accelera, ma non ci si può fidare solo di quello: WorkManager lo riprogramma
     * e le modifiche già avvenute si perdono.
     */
    private fun richiestaPeriodica(p: Prefs) = PeriodicWorkRequestBuilder<VideoWorker>(p.intervalloMin.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(rete(p)).setRequiresBatteryNotLow(true).build())
        .addTag("periodico")
        .build()

    /** Rimette in piedi i lavori automatici se per qualche motivo mancano, senza toccare quelli in corso. */
    fun assicura(ctx: Context, p: Prefs) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniqueWork(LAVORO_TRIGGER, ExistingWorkPolicy.KEEP, richiestaTrigger(p))
        wm.enqueueUniquePeriodicWork(LAVORO_PERIODICO, ExistingPeriodicWorkPolicy.UPDATE, richiestaPeriodica(p))
    }

    fun attiva(ctx: Context, p: Prefs) {
        armaTrigger(ctx, p)
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(LAVORO_PERIODICO, ExistingPeriodicWorkPolicy.UPDATE, richiestaPeriodica(p))
        p.automatico = true
    }

    fun disattiva(ctx: Context, p: Prefs) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(LAVORO_TRIGGER)
        wm.cancelUniqueWork(LAVORO_PERIODICO)
        wm.cancelUniqueWork(LAVORO_RIPROVA)
        p.automatico = false
    }

    /** Un giro subito (con le stesse regole di rete delle impostazioni). */
    fun ora(ctx: Context, p: Prefs) {
        val rq = OneTimeWorkRequestBuilder<VideoWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(rete(p)).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(LAVORO_ORA, ExistingWorkPolicy.KEEP, rq)
    }
}
