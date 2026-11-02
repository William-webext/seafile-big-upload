package dev.seafilebigupload

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Un file multimediale presente sul telefono (video o foto). La chiave (nome|dimensione) è come il server lo riconosce.
 * `anno` e `mese` servono per le sottocartelle: vengono dalla data scritta nel nome (come fa lo script di riordino del server),
 * altrimenti dalla data di scatto o di modifica.
 */
data class MediaLocale(
    val nome: String, val dimensione: Long, val uri: Uri, val modificatoMs: Long, val tipo: String, val anno: Int, val mese: Int,
) {
    val chiave get() = "$nome|$dimensione"
}

object Media {
    const val VIDEO = "video"
    const val FOTO = "foto"

    /** Un file modificato da meno di questo tempo potrebbe essere ancora in scrittura (registrazione appena finita). */
    const val STABILE_MS = 90_000L

    private val DATA_NEL_NOME = Regex("(?<!\\d)(20\\d\\d)[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])(?!\\d)")

    /** (anno, mese) dalla data scritta nel nome, se c'è ed è una data possibile (non nel futuro). */
    fun annoMeseDalNome(nome: String): Pair<Int, Int>? {
        val m = DATA_NEL_NOME.find(nome) ?: return null
        return try {
            val d = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (d.isAfter(LocalDate.now().plusDays(1))) null else d.year to d.monthValue
        } catch (_: Exception) {
            null
        }
    }

    private fun annoMese(nome: String, scattoMs: Long, modificatoMs: Long): Pair<Int, Int> {
        annoMeseDalNome(nome)?.let { return it }
        val ms = if (scattoMs > 0) scattoMs else modificatoMs
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return d.year to d.monthValue
    }

    /** Cartella di destinazione sul server per questo file, secondo il raggruppamento scelto ("nessuno", "anno", "annomese"). */
    fun cartella(base: String, raggruppa: String, m: MediaLocale): String = cartella(base, raggruppa, m.anno, m.mese)

    fun cartella(base: String, raggruppa: String, anno: Int, mese: Int): String {
        val b = base.trimEnd('/')
        return when (raggruppa) {
            // Locale.ROOT: con il telefono in arabo, persiano, bengalese... le cifre sarebbero quelle della lingua e le cartelle avrebbero nomi strani
            "anno" -> "$b/%04d".format(Locale.ROOT, anno)
            "annomese" -> "$b/%04d/%02d".format(Locale.ROOT, anno, mese)
            else -> b
        }
    }

    private fun collezione(tipo: String) = if (tipo == VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** Gli album del telefono con i loro file, per la scelta guidata: nome → (foto, video), i più numerosi per primi. */
    fun album(ctx: Context, conFoto: Boolean, conVideo: Boolean): List<Triple<String, Int, Int>> {
        val foto = HashMap<String, Int>(); val video = HashMap<String, Int>()
        fun conta(tipo: String, dove: HashMap<String, Int>) {
            ctx.contentResolver.query(collezione(tipo), arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) { val n = c.getString(0) ?: continue; dove[n] = (dove[n] ?: 0) + 1 }
            }
        }
        if (conVideo) conta(VIDEO, video)
        if (conFoto) conta(FOTO, foto)
        return (foto.keys + video.keys).toSet().map { Triple(it, foto[it] ?: 0, video[it] ?: 0) }.sortedByDescending { it.second + it.third }
    }

    /** I file degli album indicati, dei tipi richiesti. I file ancora "in corso" (IS_PENDING) MediaStore non li mostra alle altre app. */
    fun leggi(ctx: Context, album: List<String>, conFoto: Boolean, conVideo: Boolean): List<MediaLocale> {
        if (album.isEmpty()) return emptyList()
        val out = ArrayList<MediaLocale>()
        fun leggiTipo(tipo: String) {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN,
            )
            val sel = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IN (${album.joinToString(",") { "?" }})"
            ctx.contentResolver.query(collezione(tipo), proj, sel, album.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val nome = c.getString(1) ?: continue
                    val dim = c.getLong(2)
                    if (dim <= 0) continue
                    val modificato = c.getLong(3) * 1000
                    val (a, m) = annoMese(nome, c.getLong(4), modificato)
                    out.add(MediaLocale(nome, dim, ContentUris.withAppendedId(collezione(tipo), c.getLong(0)), modificato, tipo, a, m))
                }
            }
        }
        if (conVideo) leggiTipo(VIDEO)
        if (conFoto) leggiTipo(FOTO)
        return out
    }

    /** Gli indirizzi di MediaStore da osservare per accorgersi dei file nuovi. */
    fun osservati(conFoto: Boolean, conVideo: Boolean): List<Uri> = buildList {
        if (conVideo) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        if (conFoto) add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    }
}
