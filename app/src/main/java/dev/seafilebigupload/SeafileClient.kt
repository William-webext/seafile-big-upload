package dev.seafilebigupload

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class RemoteFile(val name: String, val size: Long, val dir: String)

/** Il token non è più valido (revocato dal web o scaduto): inutile riprovare finché non si rifà l'accesso. */
class AuthException(msg: String) : IOException(msg)

/** Un rifiuto definitivo del server (per esempio 413 o 403): ripetere subito non serve. */
class RifiutoException(msg: String) : IOException(msg)

/** Il caricamento è stato fermato dall'utente (pulsante "Interrompi") o dal sistema. */
class InterruptedUpload : IOException("interrupted")

/** Le poche chiamate all'API di Seafile che servono: accesso, elenco file, controllo di un file, link di caricamento, caricamento a pezzi. */
class SeafileClient(base: String, private val token: String? = null) {
    private val base = base.trim().trimEnd('/')

    init {
        // password e token viaggiano solo su connessioni cifrate: niente http (Android lo blocca già, qui si dice il perché)
        if (!this.base.startsWith("https://")) throw IllegalArgumentException(T.s(R.string.err_https_only))
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun request(path: String): Request.Builder =
        Request.Builder().url(base + path).header("User-Agent", UA)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Token $token") }

    private fun fail(what: String, code: Int, body: String, revocato: Boolean = false): IOException {
        val testo = T.s(R.string.http_error, what, code, body.replace(Regex("\\s+"), " ").take(160))
        return when {
            code == 401 || revocato -> AuthException(T.s(R.string.err_auth, what))
            code in 400..499 && code != 408 && code != 429 -> RifiutoException(testo)
            else -> IOException(testo)
        }
    }

    /**
     * Accesso con utente e password (e codice a due fattori se serve). Chiede un token PER DISPOSITIVO:
     * compare nell'elenco dei dispositivi collegati di Seafile e si può revocare da lì senza toccare gli altri accessi.
     */
    fun login(user: String, password: String, otp: String, deviceId: String, deviceName: String, androidVersion: String): String {
        val form = FormBody.Builder()
            .add("username", user).add("password", password)
            .add("platform", "android").add("device_id", deviceId).add("device_name", deviceName)
            .add("client_version", CLIENT_VERSION).add("platform_version", androidVersion)
            .build()
        val rb = request("/api2/auth-token/").post(form)
        if (otp.isNotBlank()) rb.header("X-SEAFILE-OTP", otp.trim())
        http.newCall(rb.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (r.header("X-Seafile-OTP") != null) throw IOException(T.s(R.string.err_otp))
                throw IOException(T.s(R.string.err_login, r.code, body.replace(Regex("\\s+"), " ").take(160)))
            }
            return JSONObject(body).getString("token")
        }
    }

    /** Chiede al server di revocare il token di questo dispositivo (si usa quando ci si scollega dall'app). */
    fun logout() {
        http.newCall(request("/api2/logout-device/").post(FormBody.Builder().build()).build()).execute().use { it.body?.close() }
    }

    /** Librerie su cui l'utente può scrivere: coppie (id, nome). */
    fun repos(): List<Pair<String, String>> {
        http.newCall(request("/api2/repos/").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw fail(T.s(R.string.what_libraries), r.code, body, r.header("X-Seafile-Wiped") != null)
            val a = JSONArray(body)
            return (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("permission") == "rw" }
                .map { it.getString("id") to it.getString("name") }
        }
    }

    /** Tutti i file sotto `dir`, sottocartelle comprese (le foto e i video riordinati per anno e mese restano contati). */
    fun remoteFiles(repoId: String, dir: String): List<RemoteFile> {
        val path = "/api/v2.1/repos/$repoId/dir/?p=${enc(dir)}&t=f&recursive=1"
        http.newCall(request(path).get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw fail(T.s(R.string.what_files), r.code, body, r.header("X-Seafile-Wiped") != null)
            val t = body.trimStart()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("dirent_list")
            val out = ArrayList<RemoteFile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type", "file") == "dir") continue
                out.add(RemoteFile(o.getString("name"), o.optLong("size", -1), o.optString("parent_dir", dir)))
            }
            return out
        }
    }

    /** Nomi delle sottocartelle di `dir` (solo il primo livello), per la scelta guidata della cartella. */
    fun cartelle(repoId: String, dir: String): List<String> {
        http.newCall(request("/api2/repos/$repoId/dir/?p=${enc(dir)}&t=d").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw fail(T.s(R.string.what_folders), r.code, body, r.header("X-Seafile-Wiped") != null)
            val a = JSONArray(body)
            return (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("type") == "dir" }.map { it.getString("name") }.sorted()
        }
    }

    /** Dimensione e data di modifica (secondi) del file sul server, o null se non esiste. */
    fun remoteDetail(repoId: String, path: String): Pair<Long, Long>? {
        http.newCall(request("/api2/repos/$repoId/file/detail/?p=${enc(path)}").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 404) return null
            if (!r.isSuccessful) throw fail(T.s(R.string.what_file_check), r.code, body, r.header("X-Seafile-Wiped") != null)
            val o = JSONObject(body)
            return o.optLong("size", -1) to o.optLong("mtime", 0)
        }
    }

    /** Dimensione del file sul server, o null se non esiste. */
    fun remoteSize(repoId: String, path: String): Long? = remoteDetail(repoId, path)?.first

    private fun cartellaEsiste(repoId: String, dir: String): Boolean {
        http.newCall(request("/api2/repos/$repoId/dir/?p=${enc(dir)}&t=d").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 404) return false
            if (!r.isSuccessful) throw fail(T.s(R.string.what_folder_check), r.code, body, r.header("X-Seafile-Wiped") != null)
            return true
        }
    }

    /**
     * Si assicura che la cartella `dir` esista sul server, creando un livello alla volta quelli che mancano
     * (le sottocartelle per anno e mese). `radice` è la cartella base, che deve già esserci.
     */
    fun assicuraCartella(repoId: String, radice: String, dir: String) {
        val base = radice.trimEnd('/')
        if (dir.trimEnd('/') == base) return
        var corrente = base
        for (pezzo in dir.removePrefix(base).trim('/').split('/')) {
            corrente = "$corrente/$pezzo"
            if (cartellaEsiste(repoId, corrente)) continue
            val form = FormBody.Builder().add("operation", "mkdir").build()
            http.newCall(request("/api2/repos/$repoId/dir/?p=${enc(corrente)}").post(form).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                // un altro programma (l'app ufficiale, lo script di riordino) può averla creata nel frattempo: si ricontrolla
                if (!r.isSuccessful && !cartellaEsiste(repoId, corrente)) throw fail(T.s(R.string.what_make_folder, corrente), r.code, body, r.header("X-Seafile-Wiped") != null)
            }
        }
    }

    fun uploadLink(repoId: String, dir: String): String {
        http.newCall(request("/api2/repos/$repoId/upload-link/?p=${enc(dir)}").get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw fail(T.s(R.string.what_upload_link), r.code, body, r.header("X-Seafile-Wiped") != null)
            val link = body.trim().trim('"')
            if (!link.startsWith("https://")) throw RifiutoException(T.s(R.string.err_link_insecure))
            return link
        }
    }

    /**
     * Carica un file nella cartella `parentDir`. Sopra gli 8 MiB lo manda a pezzi con Content-Range, come fa
     * l'app Seafile sopra i 200 MiB: ogni richiesta resta molto sotto il limite di 100 MB di Cloudflare.
     * Se esiste già un file con quel nome il server non lo sovrascrive (replace=0). `fermato` si controlla tra un pezzo e l'altro.
     */
    fun upload(link: String, parentDir: String, name: String, size: Long, modificatoMs: Long, open: () -> InputStream, fermato: () -> Boolean, progress: (Long) -> Unit) {
        // data di modifica originale del file, come la vuole il fileserver di Seafile (campo last_modify, RFC 3339); se non la accetta la ignora
        val lastModify = if (modificatoMs > 0) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(modificatoMs)) else null
        open().use { input ->
            if (size <= CHUNK) {
                val buf = ByteArray(size.toInt())
                if (readFully(input, buf) != size.toInt()) throw IOException(T.s(R.string.err_incomplete_read, name))
                sendChunk(link, parentDir, name, buf, buf.size, null, lastModify, fermato)
                progress(size)
                return
            }
            val buf = ByteArray(CHUNK)
            var start = 0L
            while (start < size) {
                if (fermato()) throw InterruptedUpload()
                val n = readFully(input, buf)
                if (n <= 0) throw IOException(T.s(R.string.err_read_interrupted, name, start))
                sendChunk(link, parentDir, name, buf, n, Triple(start, start + n - 1, size), lastModify, fermato)
                start += n
                progress(start)
            }
        }
    }

    private fun sendChunk(link: String, parentDir: String, name: String, buf: ByteArray, n: Int, range: Triple<Long, Long, Long>?, lastModify: String?, fermato: () -> Boolean) {
        var errore: IOException? = null
        for (tentativo in 1..3) {
            try {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("parent_dir", parentDir)
                    .addFormDataPart("replace", "0")
                if (lastModify != null) builder.addFormDataPart("last_modify", lastModify)
                val body = builder
                    .addFormDataPart("file", name, buf.toRequestBody("application/octet-stream".toMediaType(), 0, n))
                    .build()
                val rb = Request.Builder().url("$link?ret-json=1").header("User-Agent", UA).post(body)
                if (range != null) {
                    rb.header("Content-Range", "bytes ${range.first}-${range.second}/${range.third}")
                    rb.header("Content-Disposition", "attachment; filename=\"${enc(name)}\"")
                }
                http.newCall(rb.build()).execute().use { r ->
                    val resp = r.body?.string().orEmpty()
                    if (r.isSuccessful) return
                    val e = fail(T.s(R.string.what_upload, name), r.code, resp, r.header("X-Seafile-Wiped") != null)
                    if (e is AuthException || e is RifiutoException) throw e
                    errore = e
                }
            } catch (e: AuthException) {
                throw e
            } catch (e: RifiutoException) {
                throw e
            } catch (e: IOException) {
                errore = e
            }
            if (tentativo < 3) pausa(5000L * tentativo, fermato)
        }
        throw errore ?: IOException(T.s(R.string.err_upload_failed, name))
    }

    /** Aspetta `ms` millisecondi, ma si sveglia subito (con InterruptedUpload) se l'utente preme "Interrompi". */
    private fun pausa(ms: Long, fermato: () -> Boolean) {
        val fine = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fine) {
            if (fermato()) throw InterruptedUpload()
            Thread.sleep(250)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var tot = 0
        while (tot < buf.size) {
            val n = input.read(buf, tot, buf.size - tot)
            if (n < 0) break
            tot += n
        }
        return tot
    }

    companion object {
        const val CHUNK = 8 * 1024 * 1024
        const val CLIENT_VERSION = "0.5.4"
        const val UA = "Seafile-Big-Upload/$CLIENT_VERSION"
    }
}
