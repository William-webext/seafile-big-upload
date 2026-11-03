package dev.seafilebigupload

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** Prove sull'elenco delle librerie offerte dopo l'accesso: proprie per prime, poi le altre con di chi sono; solo quelle scrivibili. Dati inventati. */
class LibrerieTest {
    private fun elenco(vararg righe: String) = JSONArray("[" + righe.joinToString(",") + "]")
    private fun r(id: String, nome: String, tipo: String?, permesso: String, extra: String = "") =
        """{"id":"$id","name":"$nome","permission":"$permesso"${if (tipo != null) ""","type":"$tipo"""" else ""}$extra}"""

    @Test
    fun proprieAlPrimoPosto_poiLeAltreConIlProprietario() {
        val a = elenco(
            r("2", "Archivio", "srepo", "rw", ""","owner":"a@x.org","owner_name":"Anna","owner_contact_email":"anna@x.org""""),
            r("3", "Famiglia", "grepo", "rw", ""","group_name":"I Rossi","owner":"I Rossi""""),
            r("1", "Foto", "repo", "rw"),
        )
        assertEquals(
            listOf(
                Libreria("1", "Foto", Origine.PROPRIA, ""),
                Libreria("2", "Archivio", Origine.CONDIVISA, "Anna"),
                Libreria("3", "Famiglia", Origine.GRUPPO, "I Rossi"),
            ),
            SeafileClient.librerie(a)
        )
    }

    @Test
    fun soloScrivibili_inOrdineAlfabetico_perGruppo() {
        val a = elenco(
            r("1", "zeta", "repo", "rw"),
            r("2", "Alfa", "repo", "rw"),
            r("3", "Sola lettura", "repo", "r"),
            r("4", "beta", "repo", "rw"),
        )
        assertEquals(listOf("Alfa", "beta", "zeta"), SeafileClient.librerie(a).map { it.nome })
    }

    @Test
    fun proprietarioDiUnaCondivisa_sceglieIlNomePiuLeggibile() {
        fun di(extra: String) = SeafileClient.librerie(elenco(r("1", "L", "srepo", "rw", extra))).single().di
        assertEquals("Anna", di(""","owner":"id@auth.local","owner_name":"Anna","owner_contact_email":"anna@x.org""""))
        assertEquals("anna@x.org", di(""","owner":"id@auth.local","owner_name":"","owner_contact_email":"anna@x.org""""))
        assertEquals("id@auth.local", di(""","owner":"id@auth.local""""))
    }

    @Test
    fun senzaCampoType_siPresumePropria() {
        assertEquals(listOf(Libreria("1", "Foto", Origine.PROPRIA, "")), SeafileClient.librerie(elenco(r("1", "Foto", null, "rw"))))
    }
}
