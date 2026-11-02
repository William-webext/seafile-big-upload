package dev.seafilebigupload

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Accesso con SSO (per esempio Google) come fa l'app Seafile ufficiale: una WebView apre `/shib-login` con i dati del dispositivo,
 * il server fa il giro dell'identity provider e alla fine scrive il token per dispositivo nel cookie `seahub_auth` (`email@token`).
 * Qui solo la logica pura (indirizzo da aprire, lettura del cookie); la schermata è `SsoActivity`.
 */
object Sso {
    const val COOKIE = "seahub_auth"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Indirizzo da aprire nella WebView per far partire l'accesso; i parametri `shib_*` servono al server per creare il token del dispositivo. */
    fun indirizzoAccesso(base: String, deviceId: String, deviceName: String, clientVersion: String, androidVersion: String): String =
        base.trim().trimEnd('/') + "/shib-login?" + listOf(
            "shib_platform" to "android",
            "shib_device_id" to deviceId,
            "shib_device_name" to deviceName,
            "shib_client_version" to clientVersion,
            "shib_platform_version" to androidVersion,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    /** Email e token letti dal cookie `seahub_auth` dell'intestazione `Cookie` (come la restituisce CookieManager), o null se non c'è o non ha la forma attesa. */
    fun leggiCookie(intestazione: String?): Pair<String, String>? {
        if (intestazione.isNullOrBlank()) return null
        val grezzo = intestazione.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }?.substring(COOKIE.length + 1) ?: return null
        var v = grezzo.trim()
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.substring(1, v.length - 1)
        // Django può scrivere i caratteri speciali in ottale (\100 = @, \054 = virgola) o l'email con %40
        v = Regex("\\\\([0-7]{3})").replace(v) { it.groupValues[1].toInt(8).toChar().toString() }
        if (v.contains('%')) v = try { URLDecoder.decode(v, "UTF-8") } catch (_: IllegalArgumentException) { v }
        // l'email può contenere una chiocciola: il token è tutto quello che segue l'ultima
        val i = v.lastIndexOf('@')
        if (i <= 0) return null
        val email = v.substring(0, i); val token = v.substring(i + 1)
        if (!email.contains('@') || !token.matches(Regex("[0-9A-Za-z]{8,128}"))) return null
        return email to token
    }
}
