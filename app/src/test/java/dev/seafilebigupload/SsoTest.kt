package dev.seafilebigupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Prove sulla logica pura dell'accesso con SSO: indirizzo da aprire e lettura del cookie `seahub_auth`. Dati inventati, nessun server vero. */
class SsoTest {
    private val token = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun indirizzo_haITuttiIParametri() {
        val u = Sso.indirizzoAccesso("https://seafile.example.com/", "a1b2c3d4e5f60718", "Seafile Big Upload (SM-S911B)", "0.6.0", "15")
        assertEquals(
            "https://seafile.example.com/shib-login?shib_platform=android&shib_device_id=a1b2c3d4e5f60718" +
                "&shib_device_name=Seafile%20Big%20Upload%20%28SM-S911B%29&shib_client_version=0.6.0&shib_platform_version=15", u
        )
    }

    @Test
    fun cookie_semplice() {
        assertEquals("utente@example.com" to token, Sso.leggiCookie("seahub_auth=utente@example.com@$token"))
    }

    @Test
    fun cookie_traAltriCookie() {
        val c = "sessionid=abc; seahub_auth=utente@example.com@$token; csrftoken=xyz"
        assertEquals("utente@example.com" to token, Sso.leggiCookie(c))
    }

    @Test
    fun cookie_tra_virgolette_e_con_codifiche() {
        assertEquals("utente@example.com" to token, Sso.leggiCookie("seahub_auth=\"utente@example.com@$token\""))
        assertEquals("utente@example.com" to token, Sso.leggiCookie("seahub_auth=utente%40example.com%40$token"))
        assertEquals("utente@example.com" to token, Sso.leggiCookie("seahub_auth=\"utente\\100example.com\\100$token\""))
    }

    @Test
    fun cookie_assenteOMalformato_null() {
        assertNull(Sso.leggiCookie(null))
        assertNull(Sso.leggiCookie(""))
        assertNull(Sso.leggiCookie("sessionid=abc"))
        assertNull(Sso.leggiCookie("seahub_auth="))
        assertNull(Sso.leggiCookie("seahub_auth=$token"))                    // manca l'email
        assertNull(Sso.leggiCookie("seahub_auth=utente@example.com@"))       // manca il token
        assertNull(Sso.leggiCookie("seahub_auth=utente@example.com@abc"))    // token troppo corto
        assertNull(Sso.leggiCookie("seahub_auth=utente@example.com@tok en!"))
        assertNull(Sso.leggiCookie("xseahub_auth=utente@example.com@$token"))   // un altro cookie con nome simile
    }
}
