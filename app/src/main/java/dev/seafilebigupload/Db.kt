package dev.seafilebigupload

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Il registro dei file (video e foto): cosa è già sul server e cosa no. È la memoria dell'app, così non serve rileggere
 * ogni volta l'elenco dal server. Chiave = nome|dimensione (è così che il server li riconosce).
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "video.db", null, 2) {

    object Stato {
        const val DA_CARICARE = "da_caricare"
        const val FATTO_SERVER = "fatto_server"   // era già sul server al confronto iniziale
        const val FATTO = "fatto"                 // caricato da questa app e controllato sul server
        const val ASSENTE = "assente"             // non è più sul telefono
    }

    class Riga(
        val chiave: String, val nome: String, val dimensione: Long, val stato: String, val errore: String,
        val tentativi: Int, val prossimoMs: Long, val aggiornatoMs: Long, val tipo: String,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE video (
                chiave TEXT PRIMARY KEY, nome TEXT NOT NULL, dimensione INTEGER NOT NULL, stato TEXT NOT NULL,
                percorso_remoto TEXT, errore TEXT NOT NULL DEFAULT '', tentativi INTEGER NOT NULL DEFAULT 0,
                prossimo_ms INTEGER NOT NULL DEFAULT 0, aggiornato_ms INTEGER NOT NULL,
                tipo TEXT NOT NULL DEFAULT 'video')""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, vecchia: Int, nuova: Int) {
        if (vecchia < 2) db.execSQL("ALTER TABLE video ADD COLUMN tipo TEXT NOT NULL DEFAULT 'video'")
    }

    @Synchronized
    fun stato(chiave: String): String? =
        readableDatabase.rawQuery("SELECT stato FROM video WHERE chiave = ?", arrayOf(chiave)).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Aggiunge il file come "da caricare" se non c'è ancora; uno segnato "assente" che torna sul telefono riparte. */
    @Synchronized
    fun registra(v: MediaLocale) {
        val s = stato(v.chiave)
        if (s == null) {
            writableDatabase.insert("video", null, ContentValues().apply {
                put("chiave", v.chiave); put("nome", v.nome); put("dimensione", v.dimensione); put("tipo", v.tipo)
                put("stato", Stato.DA_CARICARE); put("aggiornato_ms", System.currentTimeMillis())
            })
        } else if (s == Stato.ASSENTE) {
            imposta(v.chiave, Stato.DA_CARICARE)
        }
    }

    /**
     * Registra in una sola transazione i file che non ci sono ancora (velocissimo anche con migliaia di foto);
     * uno segnato "assente" che torna sul telefono riparte.
     */
    @Synchronized
    fun registraNuovi(locali: List<MediaLocale>) {
        val noti = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT chiave, stato FROM video", null).use { c -> while (c.moveToNext()) noti[c.getString(0)] = c.getString(1) }
        val d = writableDatabase
        d.beginTransaction()
        try {
            val ora = System.currentTimeMillis()
            for (v in locali) {
                val s = noti[v.chiave]
                if (s == null) {
                    d.insert("video", null, ContentValues().apply {
                        put("chiave", v.chiave); put("nome", v.nome); put("dimensione", v.dimensione); put("tipo", v.tipo)
                        put("stato", Stato.DA_CARICARE); put("aggiornato_ms", ora)
                    })
                } else if (s == Stato.ASSENTE) {
                    d.update("video", ContentValues().apply {
                        put("stato", Stato.DA_CARICARE); put("errore", ""); put("tentativi", 0); put("prossimo_ms", 0); put("aggiornato_ms", ora)
                    }, "chiave = ?", arrayOf(v.chiave))
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Segna come già sul server i file indicati che sono ancora "da caricare". Restituisce quanti ne ha cambiati. */
    @Synchronized
    fun segnaSulServer(chiavi: Collection<String>): Int {
        val d = writableDatabase
        var n = 0
        d.beginTransaction()
        try {
            val ora = System.currentTimeMillis()
            for (k in chiavi) {
                n += d.update("video", ContentValues().apply {
                    put("stato", Stato.FATTO_SERVER); put("errore", ""); put("tentativi", 0); put("prossimo_ms", 0); put("aggiornato_ms", ora)
                }, "chiave = ? AND stato = ?", arrayOf(k, Stato.DA_CARICARE))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return n
    }

    @Synchronized
    fun imposta(chiave: String, stato: String, remoto: String? = null) {
        writableDatabase.update("video", ContentValues().apply {
            put("stato", stato); put("errore", ""); put("tentativi", 0); put("prossimo_ms", 0)
            put("aggiornato_ms", System.currentTimeMillis()); if (remoto != null) put("percorso_remoto", remoto)
        }, "chiave = ?", arrayOf(chiave))
    }

    /** Registra un errore e rimanda il prossimo tentativo: 15 min, 1 h, 4 h, 16 h, poi una volta al giorno. */
    @Synchronized
    fun fallito(chiave: String, errore: String, definitivoPerOggi: Boolean) {
        val n = (readableDatabase.rawQuery("SELECT tentativi FROM video WHERE chiave = ?", arrayOf(chiave)).use { if (it.moveToFirst()) it.getInt(0) else 0 }) + 1
        val attesaMin = if (definitivoPerOggi) 24 * 60L else minOf(24 * 60L, 15L shl (2 * (n - 1).coerceAtMost(4)))
        writableDatabase.update("video", ContentValues().apply {
            put("errore", errore.take(300)); put("tentativi", n)
            put("prossimo_ms", System.currentTimeMillis() + attesaMin * 60_000L); put("aggiornato_ms", System.currentTimeMillis())
        }, "chiave = ?", arrayOf(chiave))
    }

    /** Segna come assenti i file "da caricare" che non sono più sul telefono (cancellati). */
    @Synchronized
    fun segnaAssenti(presenti: Set<String>) {
        for (r in tutti()) if (r.stato == Stato.DA_CARICARE && r.chiave !in presenti) imposta(r.chiave, Stato.ASSENTE)
    }

    @Synchronized
    fun tutti(): List<Riga> = readableDatabase.rawQuery(
        "SELECT chiave, nome, dimensione, stato, errore, tentativi, prossimo_ms, aggiornato_ms, tipo FROM video ORDER BY nome", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(Riga(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getInt(5), c.getLong(6), c.getLong(7), c.getString(8))) }
    }

    @Synchronized
    fun conteggi(): Map<String, Int> = readableDatabase.rawQuery("SELECT stato, COUNT(*) FROM video GROUP BY stato", null)
        .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    @Synchronized
    fun svuota() { writableDatabase.delete("video", null, null) }

    /** Tutto il registro come JSON, per salvarlo in un file (cambio telefono, reinstallazione con un'altra chiave di firma). */
    @Synchronized
    fun esporta(): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT chiave, nome, dimensione, stato, percorso_remoto, errore, tentativi, prossimo_ms, aggiornato_ms, tipo FROM video", null,
        ).use { c ->
            while (c.moveToNext()) out.put(JSONObject().apply {
                put("chiave", c.getString(0)); put("nome", c.getString(1)); put("dimensione", c.getLong(2)); put("stato", c.getString(3))
                put("percorso_remoto", c.getString(4) ?: JSONObject.NULL); put("errore", c.getString(5)); put("tentativi", c.getInt(6))
                put("prossimo_ms", c.getLong(7)); put("aggiornato_ms", c.getLong(8)); put("tipo", c.getString(9))
            })
        }
        return out
    }

    /** Riprende un registro esportato: chi c'è già viene sostituito con la riga importata. Restituisce quante righe. */
    @Synchronized
    fun importa(righe: JSONArray): Int {
        val d = writableDatabase
        var n = 0
        d.beginTransaction()
        try {
            for (i in 0 until righe.length()) {
                val o = righe.getJSONObject(i)
                d.insertWithOnConflict("video", null, ContentValues().apply {
                    put("chiave", o.getString("chiave")); put("nome", o.getString("nome")); put("dimensione", o.getLong("dimensione"))
                    put("stato", o.getString("stato")); put("percorso_remoto", if (o.isNull("percorso_remoto")) null else o.getString("percorso_remoto"))
                    put("errore", o.optString("errore", "")); put("tentativi", o.optInt("tentativi", 0))
                    put("prossimo_ms", o.optLong("prossimo_ms", 0)); put("aggiornato_ms", o.optLong("aggiornato_ms", System.currentTimeMillis()))
                    put("tipo", o.optString("tipo", "video"))
                }, SQLiteDatabase.CONFLICT_REPLACE)
                n++
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return n
    }
}
