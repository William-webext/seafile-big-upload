package dev.seafilebigupload

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** L'applicazione: all'avvio applica la lingua scelta dall'utente. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        istanza = this
        // con "automatica" non si tocca niente: se l'utente ha scelto una lingua per l'app dalle impostazioni di Android, deve restare
        if (Lingua.codice(this) != Lingua.AUTO) Lingua.applica(this)
    }

    companion object {
        lateinit var istanza: App
    }
}

/**
 * Lingua dell'app. "auto" = quella del sistema: inglese (i testi di base) a meno che il telefono sia in italiano,
 * come per ogni app Android. Si può scegliere "en" o "it" dalle impostazioni, senza cambiare la lingua del telefono.
 */
object Lingua {
    const val AUTO = "auto"

    fun codice(ctx: Context): String = Prefs(ctx).lingua

    /** Dice ad AppCompat di usare la lingua scelta; le schermate si ricreano da sole. */
    fun applica(ctx: Context) {
        val l = codice(ctx)
        AppCompatDelegate.setApplicationLocales(if (l == AUTO) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(l))
    }

    /** Un contesto con la lingua scelta (per i testi prodotti fuori dalle schermate: lavoro in sottofondo, notifiche, errori). */
    fun contesto(base: Context): Context {
        val l = codice(base)
        if (l == AUTO) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(l))
        return base.createConfigurationContext(cfg)
    }

    fun locale(base: Context): Locale = contesto(base).resources.configuration.locales[0]
}

/** Testi tradotti da qualunque punto del codice. */
object T {
    fun s(@StringRes id: Int, vararg args: Any): String = Lingua.contesto(App.istanza).getString(id, *args)
    fun locale(): Locale = Lingua.locale(App.istanza)
}
