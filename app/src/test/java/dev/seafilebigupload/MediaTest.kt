package dev.seafilebigupload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Prove sulla logica pura: la data scritta nel nome dei file e i nomi delle cartelle sul server. Niente Android, niente rete. */
class MediaTest {
    private val localePredefinita = Locale.getDefault()

    @After
    fun ripristinaLocale() = Locale.setDefault(localePredefinita)

    @Test
    fun dataNelNome_formatiDeiTelefoni() {
        assertEquals(2026 to 11, Media.annoMeseDalNome("20261101_225737.mp4"))          // Samsung
        assertEquals(2025 to 9, Media.annoMeseDalNome("IMG_20250912_110620.jpg"))       // Xiaomi, Pixel
        assertEquals(2024 to 1, Media.annoMeseDalNome("IMG-20240101-WA0001.jpg"))       // WhatsApp
        assertEquals(2024 to 3, Media.annoMeseDalNome("VID_2024-03-05_10.mp4"))         // con i trattini
        assertEquals(2024 to 2, Media.annoMeseDalNome("20240229_120000.jpg"))           // anno bisestile
    }

    @Test
    fun dataNelNome_sbagliataOAssente_siIgnora() {
        assertNull(Media.annoMeseDalNome("20230229_120000.jpg"))     // il 29 febbraio 2023 non esiste
        assertNull(Media.annoMeseDalNome("20261301_x.jpg"))          // mese 13
        assertNull(Media.annoMeseDalNome("99991231_x.jpg"))          // fuori dal secolo previsto
        assertNull(Media.annoMeseDalNome("20990101_000000.jpg"))     // data nel futuro
        assertNull(Media.annoMeseDalNome("1202611011.jpg"))          // numero più lungo: non è una data
        assertNull(Media.annoMeseDalNome("foto.jpg"))
    }

    @Test
    fun cartella_formaDeiPercorsi() {
        assertEquals("/My Photos/Camera", Media.cartella("/My Photos/Camera/", "nessuno", 2026, 11))
        assertEquals("/My Photos/Camera/2026", Media.cartella("/My Photos/Camera", "anno", 2026, 11))
        assertEquals("/My Photos/Camera/2026/03", Media.cartella("/My Photos/Camera", "annomese", 2026, 3))
        assertEquals("/My Photos/Camera", Media.cartella("/My Photos/Camera", "valore-sconosciuto", 2026, 3))
    }

    @Test
    fun cartella_cifreLatineConQualsiasiLinguaDelTelefono() {
        // con il telefono in arabo, persiano, bengalese o marathi Java scrive le cifre della lingua: le cartelle devono restare 2026/03
        for (lingua in listOf("ar", "fa", "bn", "mr", "hi", "it", "en")) {
            Locale.setDefault(Locale.forLanguageTag(lingua))
            assertEquals("lingua $lingua", "/base/2026/03", Media.cartella("/base", "annomese", 2026, 3))
            assertEquals("lingua $lingua", "/base/2026", Media.cartella("/base", "anno", 2026, 3))
        }
    }
}
