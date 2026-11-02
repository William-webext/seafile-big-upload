package dev.seafilebigupload

import android.content.Context
import java.security.SecureRandom

/** Impostazioni salvate sul telefono. La password non viene mai salvata; il token di accesso è cifrato con il Keystore. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim().trimEnd('/')).apply()
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()

    /** Token per dispositivo (compare nell'elenco dei dispositivi collegati di Seafile e si revoca da lì). */
    var token: String
        get() = tokenInMemoria ?: (sp.getString("tokenCifrato", "") ?: "").let { if (it.isEmpty()) "" else Cripto.decifra(it) }
            .also { if (it.isNotEmpty()) tokenInMemoria = it }   // un risultato vuoto (Keystore momentaneamente non disponibile) non si ricorda
        set(v) {
            sp.edit().putString("tokenCifrato", if (v.isEmpty()) "" else Cripto.cifra(v)).apply()
            tokenInMemoria = v.ifEmpty { null }
        }

    /** Identificativo di questa installazione per Seafile: per Android il server accetta da 1 a 16 caratteri esadecimali (creato una volta). */
    val deviceId: String
        get() = sp.getString("deviceId16", null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { sp.edit().putString("deviceId16", it).apply() }

    var repoId: String
        get() = sp.getString("repoId", "") ?: ""
        set(v) = sp.edit().putString("repoId", v).apply()
    var repoName: String
        get() = sp.getString("repoName", "") ?: ""
        set(v) = sp.edit().putString("repoName", v).apply()

    /** Cartella della libreria dove vanno i video (la stessa dove l'app Seafile mette le foto). */
    var remoteDir: String
        get() = sp.getString("remoteDir", "/My Photos/Camera") ?: "/My Photos/Camera"
        set(v) = sp.edit().putString("remoteDir", "/" + v.trim().trim('/')).apply()

    /** Album del telefono da cui prendere i video (BUCKET_DISPLAY_NAME di MediaStore), separati da virgola. */
    var album: String
        get() = sp.getString("album", "Camera") ?: "Camera"
        set(v) = sp.edit().putString("album", v.trim()).apply()
    val albumLista: List<String> get() = album.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Lingua dell'app: "auto" (quella del sistema: inglese, o italiano se il telefono è in italiano), "en" o "it". */
    var lingua: String
        get() = sp.getString("lingua", "auto") ?: "auto"
        set(v) = sp.edit().putString("lingua", v).apply()

    /** Cosa caricare: "video" (predefinito), "foto" oppure "entrambi". */
    var tipi: String
        get() = sp.getString("tipi", "video") ?: "video"
        set(v) = sp.edit().putString("tipi", v).apply()
    val conVideo: Boolean get() = tipi != "foto"
    val conFoto: Boolean get() = tipi != "video"

    /** Sottocartelle sul server: "nessuno" (tutto nella cartella scelta, come prima), "anno" (AAAA) oppure "annomese" (AAAA/MM). */
    var raggruppa: String
        get() = sp.getString("raggruppa", "nessuno") ?: "nessuno"
        set(v) = sp.edit().putString("raggruppa", v).apply()

    /** Ogni quanti minuti il controllo di sicurezza guarda se c'è qualcosa da caricare (15, 60, 360, 1440, 10080). */
    var intervalloMin: Int
        get() = sp.getInt("intervalloMin", 60)
        set(v) = sp.edit().putInt("intervalloMin", v).apply()

    /** Carica solo con il Wi-Fi (rete non a consumo). */
    var soloWifi: Boolean
        get() = sp.getBoolean("soloWifi", true)
        set(v) = sp.edit().putBoolean("soloWifi", v).apply()

    /** Il confronto iniziale con il server (video già presenti) è stato fatto per questa libreria/cartella. */
    var riconciliato: Boolean
        get() = sp.getBoolean("riconciliato", false)
        set(v) = sp.edit().putBoolean("riconciliato", v).apply()

    var automatico: Boolean
        get() = sp.getBoolean("automatico", false)
        set(v) = sp.edit().putBoolean("automatico", v).apply()

    var ultimoEsito: String
        get() = sp.getString("ultimoEsito", "") ?: ""
        set(v) = sp.edit().putString("ultimoEsito", v).apply()

    /** Notifica anche quando un giro finisce bene (gli errori e l'accesso scaduto si segnalano sempre). */
    var notifiche: Boolean
        get() = sp.getBoolean("notifiche", true)
        set(v) = sp.edit().putBoolean("notifiche", v).apply()

    /** Richiesta di fermare il giro in corso (il pulsante "Interrompi"); il giro la legge tra un pezzo e l'altro. */
    var interrompi: Boolean
        get() = sp.getBoolean("interrompi", false)
        set(v) = sp.edit().putBoolean("interrompi", v).apply()

    // avanzamento del giro in corso, per la schermata (nome vuoto = niente in corso)
    var progNome: String
        get() = sp.getString("progNome", "") ?: ""
        set(v) = sp.edit().putString("progNome", v).apply()
    var progFatti: Long
        get() = sp.getLong("progFatti", 0)
        set(v) = sp.edit().putLong("progFatti", v).apply()
    var progTot: Long
        get() = sp.getLong("progTot", 0)
        set(v) = sp.edit().putLong("progTot", v).apply()
    /** Fase del giro prima dei caricamenti (lettura dell'elenco del server, confronto): vuota se non c'è. */
    var progFase: String
        get() = sp.getString("progFase", "") ?: ""
        set(v) = sp.edit().putString("progFase", v).apply()
    var progIdx: Int
        get() = sp.getInt("progIdx", 0)
        set(v) = sp.edit().putInt("progIdx", v).apply()
    var progN: Int
        get() = sp.getInt("progN", 0)
        set(v) = sp.edit().putInt("progN", v).apply()

    /** Ultimo controllo a vuoto (niente da caricare): serve a far vedere che l'app sta lavorando. */
    var ultimoControlloMs: Long
        get() = sp.getLong("ultimoControlloMs", 0)
        set(v) = sp.edit().putLong("ultimoControlloMs", v).apply()

    // riepilogo dell'ultimo giro concluso
    var ultimoGiroMs: Long
        get() = sp.getLong("ultimoGiroMs", 0)
        set(v) = sp.edit().putLong("ultimoGiroMs", v).apply()
    var ultimoCaricati: Int
        get() = sp.getInt("ultimoCaricati", 0)
        set(v) = sp.edit().putInt("ultimoCaricati", v).apply()
    var ultimoFalliti: Int
        get() = sp.getInt("ultimoFalliti", 0)
        set(v) = sp.edit().putInt("ultimoFalliti", v).apply()
    var ultimoNote: String
        get() = sp.getString("ultimoNote", "") ?: ""
        set(v) = sp.edit().putString("ultimoNote", v).apply()

    fun azzeraProgresso() {
        sp.edit().remove("progNome").remove("progFatti").remove("progTot").remove("progIdx").remove("progN").remove("progFase").apply()
    }

    private companion object {
        /** Il token in chiaro resta solo nella memoria del processo (mai su disco): evita di rifare la decifratura a ogni lettura. */
        @Volatile var tokenInMemoria: String? = null
    }

    val configurato: Boolean get() = url.isNotEmpty() && token.isNotEmpty() && repoId.isNotEmpty()

    /** Dopo un accesso con un altro server/libreria il confronto iniziale va rifatto. */
    fun azzeraAccesso() {
        tokenInMemoria = null
        sp.edit().remove("tokenCifrato").remove("repoId").remove("repoName").putBoolean("riconciliato", false).apply()
    }
}
