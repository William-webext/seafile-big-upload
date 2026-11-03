package dev.seafilebigupload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Quello che si trova nel telefono: `saltati` = elementi di cui il sistema non dice la dimensione (file virtuali), che non si possono caricare. */
class Scansione(val voci: List<Voce>, val saltati: Int)

/** Legge i file e le cartelle scelti con il selettore di sistema (Storage Access Framework): niente permessi di archiviazione da chiedere. */
object Locali {
    private val COLONNE = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    /** File scelti uno per uno: stanno tutti direttamente nella cartella di destinazione. */
    fun daFile(ctx: Context, uris: List<Uri>): Scansione {
        val voci = ArrayList<Voce>()
        var saltati = 0
        for (u in uris) {
            try {
                ctx.contentResolver.query(u, COLONNE, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) { saltati++; return@use }
                    val nome = c.getString(0)
                    val dim = if (c.isNull(1)) -1L else c.getLong(1)
                    if (nome.isNullOrEmpty() || dim < 0) saltati++
                    else voci.add(Voce(u.toString(), nome, dim, if (c.isNull(2)) 0L else c.getLong(2), emptyList()))
                } ?: saltati++
            } catch (_: Exception) {
                saltati++
            }
        }
        return Scansione(voci, saltati)
    }

    /** Una cartella intera, con tutte le sottocartelle: sul server si ricrea la stessa struttura, a partire dal nome della cartella scelta. */
    fun daCartella(ctx: Context, albero: Uri): Scansione {
        val voci = ArrayList<Voce>()
        var saltati = 0
        val radiceId = DocumentsContract.getTreeDocumentId(albero)
        val nomeRadice = ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(albero, radiceId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }?.takeIf { it.isNotEmpty() } ?: "Cartella"
        val daVisitare = ArrayDeque<Pair<String, List<String>>>()   // (id del documento, percorso relativo)
        daVisitare.add(radiceId to listOf(nomeRadice))
        while (daVisitare.isNotEmpty()) {
            val (id, sub) = daVisitare.removeFirst()
            val figli = DocumentsContract.buildChildDocumentsUriUsingTree(albero, id)
            ctx.contentResolver.query(
                figli,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val figlioId = c.getString(0); val nome = c.getString(1); val mime = c.getString(2)
                    if (figlioId == null || nome.isNullOrEmpty()) { saltati++; continue }
                    if (mime == Document.MIME_TYPE_DIR) { daVisitare.add(figlioId to sub + nome); continue }
                    val dim = if (c.isNull(3)) -1L else c.getLong(3)
                    if (dim < 0) { saltati++; continue }
                    voci.add(Voce(DocumentsContract.buildDocumentUriUsingTree(albero, figlioId).toString(), nome, dim, if (c.isNull(4)) 0L else c.getLong(4), sub))
                }
            }
        }
        return Scansione(voci, saltati)
    }
}
