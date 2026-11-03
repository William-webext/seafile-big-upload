package dev.seafilebigupload

import org.junit.Assert.assertEquals
import org.junit.Test

/** Prove sulla logica pura del "Carica file o cartella": nomi con il suffisso (1), percorsi, conflitti e scelte. Dati inventati. */
class TrasferimentoTest {
    private fun v(nome: String, vararg sub: String) = Voce("content://x/$nome", nome, 10, 0, sub.toList())

    @Test
    fun suffisso_comeWindows() {
        assertEquals("foto (1).jpg", Trasferimento.conSuffisso("foto.jpg", 1))
        assertEquals("archivio.tar (2).gz", Trasferimento.conSuffisso("archivio.tar.gz", 2))
        assertEquals("LEGGIMI (1)", Trasferimento.conSuffisso("LEGGIMI", 1))
        assertEquals(".nascosto (1)", Trasferimento.conSuffisso(".nascosto", 1))
    }

    @Test
    fun primoLibero_saltaIOccupati() {
        assertEquals("a (1).txt", Trasferimento.primoLibero("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", Trasferimento.primoLibero("a.txt", setOf("a.txt", "a (1).txt", "a (2).txt")))
    }

    @Test
    fun dirRemota_unisceLeCartelle() {
        assertEquals("/", Trasferimento.dirRemota("/", emptyList()))
        assertEquals("/Foto", Trasferimento.dirRemota("/Foto/", emptyList()))
        assertEquals("/Foto/Viaggio/2026", Trasferimento.dirRemota("/Foto", listOf("Viaggio", "2026")))
        assertEquals("/Viaggio", Trasferimento.dirRemota("/", listOf("Viaggio")))
    }

    @Test
    fun conflitti_sulServerEFraIFile() {
        val voci = listOf(v("a.txt"), v("b.txt"), v("a.txt"), v("c.txt", "Sub"))
        val esistenti = mapOf("/D" to setOf("b.txt"), "/D/Sub" to setOf("altro.txt"))
        // b.txt esiste già sul server; il secondo a.txt finirebbe sopra il primo; c.txt va in una sottocartella dove non c'è
        assertEquals(listOf(1, 2), Trasferimento.conflitti(voci, "/D", esistenti))
    }

    @Test
    fun costruisci_seguiLeScelte() {
        val voci = listOf(v("a.txt"), v("b.txt"), v("c.txt"), v("d.txt"))
        val esistenti = mapOf("/D" to setOf("b.txt", "c.txt", "d.txt", "d (1).txt"))
        val scelte = mapOf(1 to Scelta.SOVRASCRIVI, 2 to Scelta.TIENI_ENTRAMBI, 3 to Scelta.TIENI_ENTRAMBI)
        val a = Trasferimento.costruisci(voci, "/D", esistenti, scelte)
        assertEquals(listOf("a.txt", "b.txt", "c (1).txt", "d (2).txt"), a.map { it.nomeRemoto })
        assertEquals(listOf(false, true, false, false), a.map { it.sovrascrivi })
    }

    @Test
    fun costruisci_senzaSceltaOAnnulla_siSalta() {
        val voci = listOf(v("a.txt"), v("b.txt"))
        val esistenti = mapOf("/D" to setOf("a.txt", "b.txt"))
        assertEquals(emptyList<Azione>(), Trasferimento.costruisci(voci, "/D", esistenti, mapOf(0 to Scelta.ANNULLA)))
    }

    @Test
    fun costruisci_cartellaConSottocartelle() {
        val voci = listOf(v("a.txt", "Gita"), v("b.txt", "Gita", "Foto"))
        val a = Trasferimento.costruisci(voci, "/", emptyMap(), emptyMap())
        assertEquals(listOf("/Gita", "/Gita/Foto"), a.map { it.dir })
    }

    @Test
    fun nomeNuovo_perIlDialogo() {
        assertEquals("a (1).txt", Trasferimento.nomeNuovo(v("a.txt"), "/D", mapOf("/D" to setOf("a.txt"))))
    }
}
