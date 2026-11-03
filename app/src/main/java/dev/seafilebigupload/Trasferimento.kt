package dev.seafilebigupload

/** Un file del telefono scelto a mano. `sub` = le cartelle in cui sta, relative a quella scelta (compresa la cartella scelta stessa); vuoto per un file scelto da solo. */
data class Voce(val uri: String, val nome: String, val dimensione: Long, val modificatoMs: Long, val sub: List<String>)

/** Cosa fare quando sul server esiste già un file con lo stesso nome. */
enum class Scelta { ANNULLA, SOVRASCRIVI, TIENI_ENTRAMBI }

/** Un caricamento deciso: in quale cartella del server e con quale nome. */
data class Azione(
    val uri: String, val dimensione: Long, val modificatoMs: Long,
    val dir: String, val nomeLocale: String, val nomeRemoto: String, val sovrascrivi: Boolean,
)

/** Logica pura del "Carica file o cartella": percorsi sul server, nomi con il suffisso (1), conflitti. Nessuna rete, niente Android. */
object Trasferimento {
    /** La cartella del server in cui va un file: la destinazione scelta più le sue sottocartelle (con `/` iniziale, mai finale). */
    fun dirRemota(base: String, sub: List<String>): String =
        (listOf(base.trimEnd('/')) + sub).joinToString("/").ifEmpty { "/" }

    /** `nome (n).ext` come fa Windows: il suffisso va prima dell'ultima estensione; un nome senza estensione (o che inizia con il punto) lo prende in fondo. */
    fun conSuffisso(nome: String, n: Int): String {
        val punto = nome.lastIndexOf('.')
        return if (punto <= 0) "$nome ($n)" else nome.substring(0, punto) + " ($n)" + nome.substring(punto)
    }

    /** Il primo `nome (n).ext`, con n da 1, che non è tra i nomi già occupati. */
    fun primoLibero(nome: String, occupati: Set<String>): String {
        var n = 1
        while (conSuffisso(nome, n) in occupati) n++
        return conSuffisso(nome, n)
    }

    /**
     * Gli indici dei file che troverebbero già un omonimo: sul server (`esistenti`: cartella → nomi) oppure tra i file stessi
     * (due file scelti da posti diversi con lo stesso nome finirebbero nella stessa cartella).
     */
    fun conflitti(voci: List<Voce>, base: String, esistenti: Map<String, Set<String>>): List<Int> {
        val occupati = HashMap<String, MutableSet<String>>()
        val out = ArrayList<Int>()
        for ((i, v) in voci.withIndex()) {
            val dir = dirRemota(base, v.sub)
            val presenti = occupati.getOrPut(dir) { (esistenti[dir] ?: emptySet()).toHashSet() }
            if (v.nome in presenti) out.add(i) else presenti.add(v.nome)
        }
        return out
    }

    /**
     * L'elenco finale dei caricamenti. `scelte` dice cosa fare per i file in conflitto (indice → scelta); un conflitto senza scelta
     * si salta: nel dubbio non si sovrascrive mai. Con TIENI_ENTRAMBI il nome nuovo è il primo `nome (n).ext` libero.
     */
    fun costruisci(voci: List<Voce>, base: String, esistenti: Map<String, Set<String>>, scelte: Map<Int, Scelta>): List<Azione> {
        val occupati = HashMap<String, MutableSet<String>>()
        val out = ArrayList<Azione>()
        for ((i, v) in voci.withIndex()) {
            val dir = dirRemota(base, v.sub)
            val presenti = occupati.getOrPut(dir) { (esistenti[dir] ?: emptySet()).toHashSet() }
            if (v.nome !in presenti) {
                presenti.add(v.nome)
                out.add(Azione(v.uri, v.dimensione, v.modificatoMs, dir, v.nome, v.nome, false))
                continue
            }
            when (scelte[i] ?: Scelta.ANNULLA) {
                Scelta.ANNULLA -> {}
                Scelta.SOVRASCRIVI -> out.add(Azione(v.uri, v.dimensione, v.modificatoMs, dir, v.nome, v.nome, true))
                Scelta.TIENI_ENTRAMBI -> {
                    val nuovo = primoLibero(v.nome, presenti)
                    presenti.add(nuovo)
                    out.add(Azione(v.uri, v.dimensione, v.modificatoMs, dir, v.nome, nuovo, false))
                }
            }
        }
        return out
    }

    /** Il nome che avrebbe il file con TIENI_ENTRAMBI, per dirlo nel dialogo prima che l'utente scelga. */
    fun nomeNuovo(v: Voce, base: String, esistenti: Map<String, Set<String>>): String =
        primoLibero(v.nome, esistenti[dirRemota(base, v.sub)] ?: emptySet())
}
