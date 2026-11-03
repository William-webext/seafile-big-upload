package dev.seafilebigupload

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import com.google.android.material.R as M

/**
 * Schermata unica, sul modello delle impostazioni dell'app Seafile: in alto lo stato di quello che sta succedendo,
 * poi interruttori che dicono sempre se una cosa è accesa o spenta, le scelte guidate e i controlli di funzionamento.
 * Tutti i testi stanno nelle risorse (inglese di base, italiano in values-it).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var p: Prefs
    private lateinit var db: Db
    private lateinit var radice: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var aggiornandoInterruttori = false
    private var premutoMs = 0L   // quando l'utente ha premuto "Carica ora": la schermata mostra subito "Avvio…", senza aspettare WorkManager

    // stato del lavoro in sottofondo, aggiornato da WorkManager
    private val statoLavori = HashMap<String, List<WorkInfo>>()

    // scheda di stato
    private lateinit var statoTitolo: TextView
    private lateinit var statoDettaglio: TextView
    private lateinit var barra: LinearProgressIndicator
    private lateinit var barraTesto: TextView
    private lateinit var contatori: TextView
    private lateinit var errori: TextView
    private lateinit var bCarica: MaterialButton
    private lateinit var bInterrompi: MaterialButton

    // accesso
    private lateinit var cardAccesso: MaterialCardView
    private lateinit var url: TextInputEditText
    private lateinit var utente: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var otp: TextInputEditText
    private lateinit var bAccedi: MaterialButton
    private lateinit var bGoogle: MaterialButton

    // ritorno dalla schermata di accesso con SSO: se è andata bene il token è già salvato e resta la verifica di sempre
    private val sso = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && p.token.isNotEmpty()) dopoSso() else messaggio(getString(R.string.sso_cancelled))
    }

    // righe
    private lateinit var rAuto: Ui.Riga
    private lateinit var rWifi: Ui.Riga
    private lateinit var rNotifiche: Ui.Riga
    private lateinit var rFrequenza: Ui.Riga
    private lateinit var rTipi: Ui.Riga
    private lateinit var rAlbum: Ui.Riga
    private lateinit var rCartella: Ui.Riga
    private lateinit var rRaggruppa: Ui.Riga
    private lateinit var rAccount: Ui.Riga
    private lateinit var rBatteria: Ui.Riga
    private lateinit var rMedia: Ui.Riga
    private lateinit var rAvvisi: Ui.Riga
    private lateinit var rConfronto: Ui.Riga
    private lateinit var rEsporta: Ui.Riga
    private lateinit var rImporta: Ui.Riga
    private lateinit var rLingua: Ui.Riga
    private lateinit var rInfo: Ui.Riga

    private val esportaLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) esportaIn(uri) }
    private val importaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importaDa(uri) }

    private val aggiorna = object : Runnable {
        override fun run() {
            mostra()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        p = Prefs(this)
        db = Db(this)

        radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applicaInsets(radice)
        radice.addView(MaterialToolbar(this).apply {
            title = getString(R.string.title_main)
            setTitleTextColor(Ui.testo(context))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))

        val colonna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dp(this@MainActivity, 32)) }
        radice.addView(ScrollView(this).apply { addView(colonna) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)

        costruisciStato(colonna)
        costruisciAccesso(colonna)

        colonna.addView(Ui.sezione(this, getString(R.string.sec_upload)))
        rAuto = Ui.riga(this, getString(R.string.row_auto), "", conInterruttore = true).also { colonna.addView(it.vista) }
        rWifi = Ui.riga(this, getString(R.string.row_wifi), "", conInterruttore = true).also { colonna.addView(it.vista) }
        rNotifiche = Ui.riga(this, getString(R.string.row_notify), "", conInterruttore = true).also { colonna.addView(it.vista) }
        rFrequenza = Ui.riga(this, getString(R.string.row_freq), "") { scegliFrequenza() }.also { colonna.addView(it.vista) }

        colonna.addView(Ui.sezione(this, getString(R.string.sec_what)))
        rTipi = Ui.riga(this, getString(R.string.row_types), "") { scegliTipi() }.also { colonna.addView(it.vista) }
        rAlbum = Ui.riga(this, getString(R.string.row_album), "") { scegliAlbum() }.also { colonna.addView(it.vista) }
        rCartella = Ui.riga(this, getString(R.string.row_folder), "") { sfogliaCartella("/") }.also { colonna.addView(it.vista) }
        rRaggruppa = Ui.riga(this, getString(R.string.row_group), "") { scegliRaggruppamento() }.also { colonna.addView(it.vista) }

        colonna.addView(Ui.sezione(this, getString(R.string.sec_account)))
        rAccount = Ui.riga(this, getString(R.string.row_account), "") { esci() }.also { colonna.addView(it.vista) }

        colonna.addView(Ui.sezione(this, getString(R.string.sec_works)))
        rBatteria = Ui.riga(this, getString(R.string.row_battery), "") { chiediBatteria() }.also { colonna.addView(it.vista) }
        rMedia = Ui.riga(this, getString(R.string.row_access), "") { permessiMedia() }.also { colonna.addView(it.vista) }
        rAvvisi = Ui.riga(this, getString(R.string.row_notif), "") { permessoNotifiche() }.also { colonna.addView(it.vista) }

        colonna.addView(Ui.sezione(this, getString(R.string.sec_adv)))
        rLingua = Ui.riga(this, getString(R.string.row_language), "") { scegliLingua() }.also { colonna.addView(it.vista) }
        rConfronto = Ui.riga(this, getString(R.string.row_redo), getString(R.string.redo_summary)) { rifaiConfronto() }.also { colonna.addView(it.vista) }
        rEsporta = Ui.riga(this, getString(R.string.row_export), getString(R.string.export_summary)) {
            esportaLauncher.launch("seafile-big-upload-registry-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json")
        }.also { colonna.addView(it.vista) }
        rImporta = Ui.riga(this, getString(R.string.row_import), getString(R.string.import_summary)) { importaLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            .also { colonna.addView(it.vista) }
        rInfo = Ui.riga(this, getString(R.string.row_info), getString(R.string.info_summary, versione())) {
            startActivity(Intent(this, InfoActivity::class.java))
        }.also { colonna.addView(it.vista) }

        collegaInterruttori()
        osservaLavori()
        chiediPermessiIniziali()
    }

    override fun onResume() {
        super.onResume()
        if (p.automatico && p.configurato) Pianifica.assicura(this, p)   // se per qualche motivo mancassero, si rimettono in piedi
        aggiorna.run()
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(aggiorna) }
    override fun onDestroy() { super.onDestroy(); db.close() }

    // ---------------------------------------------------------------- costruzione

    private fun costruisciStato(colonna: LinearLayout) {
        val ctx = this
        statoTitolo = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Ui.testo(ctx)) }
        statoDettaglio = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Ui.grigio(ctx)); setPadding(0, Ui.dp(ctx, 4), 0, 0) }
        barra = LinearProgressIndicator(ctx).apply { max = 100; visibility = View.GONE }
        barraTesto = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Ui.grigio(ctx)); setPadding(0, Ui.dp(ctx, 6), 0, 0); visibility = View.GONE }
        contatori = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Ui.testo(ctx)); setPadding(0, Ui.dp(ctx, 14), 0, 0) }
        errori = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Ui.rosso(ctx)); setPadding(0, Ui.dp(ctx, 6), 0, 0); visibility = View.GONE }
        bCarica = MaterialButton(ctx).apply { text = getString(R.string.btn_upload_now); setOnClickListener { caricaOra() } }
        bInterrompi = MaterialButton(ctx).apply { text = getString(R.string.btn_stop); visibility = View.GONE; setOnClickListener { interrompi() } }
        val bElenco = MaterialButton(ctx, null, M.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.btn_list); setOnClickListener { startActivity(Intent(ctx, ElencoActivity::class.java)) }
        }
        val pulsanti = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(ctx, 14), 0, 0)
            addView(bCarica); addView(bInterrompi)
            addView(bElenco, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 10) })
        }
        val interno = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 20), Ui.dp(ctx, 16))
            addView(statoTitolo); addView(statoDettaglio); addView(barra); addView(barraTesto)
            addView(contatori); addView(errori); addView(pulsanti)
        }
        colonna.addView(scheda(interno), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), 0)
        })
    }

    private fun scheda(contenuto: View) = MaterialCardView(this).apply {
        radius = Ui.dp(context, 20).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(Ui.colore(context, M.attr.colorSurfaceContainer))
        addView(contenuto)
    }

    private fun campo(hint: String, tipo: Int, password: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(this, null, M.attr.textInputOutlinedStyle)
        til.hint = hint
        if (password) til.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val et = TextInputEditText(til.context).apply { inputType = tipo; setSingleLine() }
        til.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return til to et
    }

    private fun costruisciAccesso(colonna: LinearLayout) {
        val ctx = this
        val (tUrl, eUrl) = campo(getString(R.string.hint_url), InputType.TYPE_TEXT_VARIATION_URI)
        val (tUtente, eUtente) = campo(getString(R.string.hint_user), InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val (tPw, ePw) = campo(getString(R.string.hint_pw), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, password = true)
        val (tOtp, eOtp) = campo(getString(R.string.hint_otp), InputType.TYPE_CLASS_NUMBER)
        url = eUrl; utente = eUtente; password = ePw; otp = eOtp
        url.setText(p.url); utente.setText(p.user)
        bAccedi = MaterialButton(ctx).apply { text = getString(R.string.btn_signin); setOnClickListener { accedi() } }
        bGoogle = MaterialButton(ctx, null, M.attr.materialButtonOutlinedStyle).apply { text = getString(R.string.btn_signin_google); setOnClickListener { accediConSso() } }
        val spiegazione = TextView(ctx).apply {
            text = getString(R.string.login_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Ui.grigio(ctx)); setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
        }
        val titolo = TextView(ctx).apply { text = getString(R.string.login_title); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Ui.testo(ctx)); setPadding(0, 0, 0, Ui.dp(ctx, 8)) }
        val interno = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 18), Ui.dp(ctx, 20), Ui.dp(ctx, 16))
            addView(titolo)
            for (t in listOf(tUrl, tUtente, tPw, tOtp)) addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(ctx, 8) })
            addView(spiegazione); addView(bAccedi)
            addView(TextView(ctx).apply {
                text = getString(R.string.sso_help)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Ui.grigio(ctx)); setPadding(0, Ui.dp(ctx, 16), 0, Ui.dp(ctx, 8))
            })
            addView(bGoogle)
        }
        cardAccesso = scheda(interno)
        colonna.addView(cardAccesso, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), 0)
        })
    }

    private fun collegaInterruttori() {
        rAuto.interruttore!!.setOnCheckedChangeListener { _, acceso ->
            if (aggiornandoInterruttori) return@setOnCheckedChangeListener
            if (!p.configurato) { messaggio(getString(R.string.signin_first)); mostra(); return@setOnCheckedChangeListener }
            if (acceso) {
                Pianifica.attiva(this, p); premutoMs = System.currentTimeMillis(); Pianifica.ora(this, p)
                messaggio(getString(R.string.auto_on_toast))
            } else {
                Pianifica.disattiva(this, p)
                messaggio(getString(R.string.auto_off_toast))
            }
            mostra()
        }
        rWifi.interruttore!!.setOnCheckedChangeListener { _, acceso ->
            if (aggiornandoInterruttori) return@setOnCheckedChangeListener
            p.soloWifi = acceso
            if (p.automatico) Pianifica.attiva(this, p)
            messaggio(getString(if (acceso) R.string.wifi_only_toast else R.string.wifi_any_toast))
            mostra()
        }
        rNotifiche.interruttore!!.setOnCheckedChangeListener { _, acceso ->
            if (aggiornandoInterruttori) return@setOnCheckedChangeListener
            p.notifiche = acceso
            messaggio(getString(if (acceso) R.string.notify_on_toast else R.string.notify_off_toast))
            mostra()
        }
    }

    private fun osservaLavori() {
        val wm = WorkManager.getInstance(this)
        for (nome in listOf(Pianifica.LAVORO_TRIGGER, Pianifica.LAVORO_PERIODICO, Pianifica.LAVORO_RIPROVA, Pianifica.LAVORO_ORA)) {
            wm.getWorkInfosForUniqueWorkLiveData(nome).observe(this) { lista -> statoLavori[nome] = lista; mostra() }
        }
    }

    // ---------------------------------------------------------------- stato mostrato

    private fun inCorso() = statoLavori.values.any { l -> l.any { it.state == WorkInfo.State.RUNNING } }

    /** "Carica ora" premuto e il lavoro non è ancora partito: in coda, o in attesa della rete. */
    private fun inAvvio(): Boolean =
        statoLavori[Pianifica.LAVORO_ORA].orEmpty().any { it.state == WorkInfo.State.ENQUEUED } ||
            (System.currentTimeMillis() - premutoMs < 6000 && p.ultimoControlloMs < premutoMs && p.ultimoGiroMs < premutoMs)

    private fun programmato(): Boolean {
        val vivi = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
        val trigger = statoLavori[Pianifica.LAVORO_TRIGGER].orEmpty().any { it.state in vivi }
        val periodico = statoLavori[Pianifica.LAVORO_PERIODICO].orEmpty().any { it.state in vivi }
        return trigger || periodico
    }

    /** C'è la rete richiesta dalle impostazioni (solo Wi-Fi = rete non a consumo)? */
    private fun reteOk(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !p.soloWifi || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun lingua(): Locale = Lingua.locale(this)

    private fun ora(ms: Long) = SimpleDateFormat(getString(R.string.date_format), lingua()).format(Date(ms))

    private fun nomeTipi() = getString(when (p.tipi) { "foto" -> R.string.types_photos; "entrambi" -> R.string.types_both; else -> R.string.types_videos })

    private fun nomeTipiCorto() = getString(when (p.tipi) { "foto" -> R.string.kind_photos; "entrambi" -> R.string.kind_both; else -> R.string.kind_videos })

    private fun nomeFrequenza(min: Int) = when (min) {
        15 -> getString(R.string.every_15m); 60 -> getString(R.string.every_1h); 360 -> getString(R.string.every_6h)
        1440 -> getString(R.string.every_1d); 10080 -> getString(R.string.every_1w)
        else -> getString(R.string.every_n, min)
    }

    private fun entro(min: Int) = when (min) {
        15 -> getString(R.string.within_15m); 60 -> getString(R.string.within_1h); 360 -> getString(R.string.within_6h)
        1440 -> getString(R.string.within_1d); 10080 -> getString(R.string.within_1w)
        else -> getString(R.string.within_n, min)
    }

    private fun nomeRaggruppamento() = when (p.raggruppa) {
        "anno" -> getString(R.string.group_year, Calendar.getInstance().get(Calendar.YEAR).toString())
        "annomese" -> getString(R.string.group_month, SimpleDateFormat("yyyy/MM", Locale.US).format(Date()))
        else -> getString(R.string.group_none)
    }

    private fun nomeLingua() = getString(when (p.lingua) { "en" -> R.string.lang_en; "it" -> R.string.lang_it; else -> R.string.lang_auto })

    private fun mostra() {
        val ctx = this
        val configurato = p.configurato
        val corso = inCorso()
        val avvio = !corso && inAvvio()
        cardAccesso.visibility = if (configurato) View.GONE else View.VISIBLE

        // --- scheda di stato
        val c = db.conteggi()
        val f = Db.Stato
        val inAttesa = c[f.DA_CARICARE] ?: 0
        val (nErrori, ultimoErrore) = db.errori()
        val titolo: String; val colore: Int; val dettaglio: String
        when {
            !configurato -> { titolo = getString(R.string.state_login_missing); colore = Ui.rosso(ctx); dettaglio = getString(R.string.state_login_missing_detail) }
            corso -> { titolo = getString(R.string.state_running); colore = Ui.arancione(ctx); dettaglio = getString(R.string.state_running_detail) }
            avvio && !reteOk() -> {
                titolo = getString(if (p.soloWifi) R.string.state_waiting_wifi else R.string.state_waiting_net); colore = Ui.arancione(ctx)
                dettaglio = getString(if (p.soloWifi) R.string.state_waiting_wifi_detail else R.string.state_waiting_net_detail)
            }
            avvio -> { titolo = getString(R.string.state_starting); colore = Ui.arancione(ctx); dettaglio = getString(R.string.state_starting_detail) }
            p.automatico && programmato() -> { titolo = getString(R.string.state_active); colore = Ui.verde(ctx); dettaglio = getString(R.string.state_active_detail, entro(p.intervalloMin)) }
            p.automatico -> { titolo = getString(R.string.state_active_waiting); colore = Ui.arancione(ctx); dettaglio = getString(R.string.state_active_waiting_detail) }
            else -> { titolo = getString(R.string.state_off); colore = Ui.grigio(ctx); dettaglio = getString(R.string.state_off_detail) }
        }
        statoTitolo.text = android.text.SpannableString("● $titolo").apply {
            setSpan(android.text.style.ForegroundColorSpan(colore), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val ultimo = when {
            p.ultimoControlloMs > p.ultimoGiroMs && p.ultimoControlloMs > 0 -> "\n" + getString(R.string.last_check_nothing, ora(p.ultimoControlloMs)) +
                (if (p.ultimoGiroMs > 0) "\n" + getString(R.string.last_upload, ora(p.ultimoGiroMs), p.ultimoCaricati, p.ultimoFalliti) else "")
            p.ultimoGiroMs > 0 -> "\n" + getString(R.string.last_run, ora(p.ultimoGiroMs), p.ultimoCaricati, p.ultimoFalliti) +
                (if (p.ultimoNote.isNotEmpty() && p.ultimoCaricati > 0) "\n${p.ultimoNote}" else "")
            else -> "\n" + getString(R.string.no_run_yet)
        }
        statoDettaglio.text = dettaglio + (if (configurato) ultimo else "")

        // --- barra di avanzamento: sempre visibile finché il lavoro è in coda o in corso, così si capisce che sta facendo qualcosa
        if ((corso || avvio) && configurato) {
            barra.visibility = View.VISIBLE; barraTesto.visibility = View.VISIBLE
            when {
                corso && p.progNome.isNotEmpty() && p.progTot > 0 -> {
                    val perc = (100 * p.progFatti / p.progTot).toInt()
                    barra.isIndeterminate = false
                    barra.setProgressCompat(perc, true)
                    barraTesto.text = getString(R.string.progress_item, p.progIdx, p.progN, p.progNome, perc, Caricatore.mb(p.progFatti), Caricatore.mb(p.progTot))
                }
                corso && p.progFase.isNotEmpty() && p.progTot > 0 -> {
                    barra.isIndeterminate = false
                    barra.setProgressCompat((100 * p.progFatti / p.progTot).toInt(), true)
                    barraTesto.text = p.progFase
                }
                corso && p.progFase.isNotEmpty() -> { barra.isIndeterminate = true; barraTesto.text = p.progFase }
                else -> { barra.isIndeterminate = true; barraTesto.text = getString(if (avvio && !reteOk()) (if (p.soloWifi) R.string.state_waiting_wifi else R.string.state_waiting_net) else R.string.state_starting) }
            }
        } else {
            barra.visibility = View.GONE; barraTesto.visibility = View.GONE
        }

        contatori.text = getString(R.string.counters, c[f.FATTO_SERVER] ?: 0, c[f.FATTO] ?: 0, inAttesa)
        if (nErrori > 0) {
            errori.visibility = View.VISIBLE
            errori.text = getString(R.string.errors_line, nErrori, ultimoErrore.take(110))
        } else errori.visibility = View.GONE

        // un solo pulsante alla volta: se il lavoro è in coda o in corso "Carica ora" sparisce, così non si preme di nuovo
        bCarica.visibility = if (corso || avvio) View.GONE else View.VISIBLE
        bInterrompi.visibility = if (corso || avvio) View.VISIBLE else View.GONE
        bCarica.isEnabled = configurato

        // --- interruttori: riflettono lo stato vero, non l'ultimo clic
        aggiornandoInterruttori = true
        rAuto.interruttore!!.isChecked = p.automatico
        rWifi.interruttore!!.isChecked = p.soloWifi
        rNotifiche.interruttore!!.isChecked = p.notifiche
        aggiornandoInterruttori = false
        rAuto.sommario.text = getString(if (!configurato) R.string.auto_signin_first else if (p.automatico) R.string.auto_on else R.string.auto_off)
        rAuto.abilita(configurato)
        rWifi.sommario.text = getString(if (p.soloWifi) R.string.wifi_on else R.string.wifi_off)
        rNotifiche.sommario.text = getString(if (p.notifiche) R.string.notify_on else R.string.notify_off)
        rFrequenza.sommario.text = getString(R.string.freq_summary, nomeFrequenza(p.intervalloMin))

        rTipi.sommario.text = nomeTipi()
        rAlbum.sommario.text = p.albumLista.joinToString(", ").ifEmpty { getString(R.string.album_none) }
        rCartella.sommario.text = if (configurato) getString(R.string.folder_summary, p.repoName, p.remoteDir) else getString(R.string.auto_signin_first)
        rCartella.abilita(configurato)
        rRaggruppa.sommario.text = getString(R.string.group_summary, nomeRaggruppamento())
        rConfronto.abilita(configurato)
        rLingua.sommario.text = nomeLingua()
        rAccount.titolo.text = getString(if (configurato) R.string.account_exit else R.string.row_account)
        rAccount.sommario.text = if (configurato) getString(R.string.account_summary, p.user, p.repoName) else getString(R.string.account_none)
        rAccount.abilita(configurato)

        // --- controlli di funzionamento
        val batteriaOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        controllo(rBatteria, batteriaOk, getString(R.string.battery_ok), getString(R.string.battery_bad))
        val media = statoMedia()
        controllo(rMedia, media == 2, getString(R.string.access_ok, nomeTipiCorto()),
            if (media == 1) getString(R.string.access_partial) else getString(R.string.access_missing, nomeTipiCorto()))
        val avvisi = NotificationManagerCompat.from(this).areNotificationsEnabled()
        controllo(rAvvisi, avvisi, getString(R.string.notif_ok), getString(R.string.notif_bad))
    }

    private fun controllo(r: Ui.Riga, ok: Boolean, testoOk: String, testoProblema: String) {
        r.sommario.text = if (ok) "✔  $testoOk" else "⚠  $testoProblema"
        r.sommario.setTextColor(if (ok) Ui.grigio(this) else Ui.rosso(this))
    }

    /** La schermata esiste ancora? Un'operazione in sottofondo che finisce dopo una rotazione o un cambio di lingua non deve mostrarle finestre. */
    private fun vivo() = !isFinishing && !isDestroyed

    private fun messaggio(t: String) = Snackbar.make(radice, t, Snackbar.LENGTH_LONG).show()

    private fun versione(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ---------------------------------------------------------------- permessi

    /** I permessi che servono per i tipi di file scelti. */
    private fun permessiNecessari(): List<String> {
        if (Build.VERSION.SDK_INT < 33) return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return buildList {
            if (p.conVideo) add(Manifest.permission.READ_MEDIA_VIDEO)
            if (p.conFoto) add(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    /** 2 = tutto accessibile, 1 = solo i file scelti (Android 14+), 0 = qualcosa manca. */
    private fun statoMedia(): Int = when {
        permessiNecessari().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } -> 2
        Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED -> 1
        else -> 0
    }

    private companion object {
        const val RICHIESTA_MEDIA = 2
    }

    private fun chiediPermessiIniziali() {
        val da = ArrayList(permessiNecessari().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) da.add(Manifest.permission.POST_NOTIFICATIONS)
        if (da.isNotEmpty()) requestPermissions(da.toTypedArray(), 1)
    }

    private fun apriImpostazioniApp(spiega: String) {
        messaggio(spiega)
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /**
     * Tocco sulla riga dei permessi: se manca qualcosa che si può ancora chiedere (per esempio le foto, dopo aver ripreso un registro
     * che le prevede) compare la richiesta di sistema; se il sistema non la mostra più (già negata) si apre la schermata dei permessi.
     */
    private fun permessiMedia() {
        if (statoMedia() == 2) { messaggio(getString(R.string.access_toast_ok)); return }
        val mancanti = permessiNecessari().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (mancanti.isNotEmpty()) requestPermissions(mancanti.toTypedArray(), RICHIESTA_MEDIA) else apriImpostazioniApp(getString(R.string.access_hint))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RICHIESTA_MEDIA && statoMedia() != 2) apriImpostazioniApp(getString(R.string.access_hint))
        mostra()
    }

    private fun permessoNotifiche() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) { messaggio(getString(R.string.notif_toast_ok)); return }
        apriImpostazioniApp(getString(R.string.notif_hint))
    }

    private fun chiediBatteria() {
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            messaggio(getString(R.string.battery_toast_ok)); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- azioni

    /** Salva indirizzo e utente. Senza schema si aggiunge https://; un indirizzo http:// si rifiuta (la password non deve viaggiare in chiaro). */
    private fun salvaAccesso(): Boolean {
        var u = url.text.toString().trim()
        if (u.isNotEmpty() && !u.contains("://")) u = "https://$u"
        if (u.startsWith("http://", ignoreCase = true)) { messaggio(getString(R.string.err_https_only)); return false }
        p.url = u; p.user = utente.text.toString()
        if (u.isNotEmpty()) url.setText(p.url)
        return true
    }

    private fun accedi() {
        if (!salvaAccesso()) return
        val pw = password.text.toString(); val codice = otp.text.toString()
        if (p.url.isEmpty() || p.user.isEmpty() || pw.isEmpty()) { messaggio(getString(R.string.enter_fields)); return }
        bAccedi.isEnabled = false; bGoogle.isEnabled = false; bAccedi.text = getString(R.string.signing_in)
        thread {
            try {
                val token = SeafileClient(p.url).login(p.user, pw, codice, p.deviceId, "Seafile Big Upload (${Build.MODEL})", Build.VERSION.RELEASE)
                p.token = token
                val repos = SeafileClient(p.url, token).repos()
                ui.post { if (!vivo()) return@post;
                    password.setText(""); otp.setText(""); bAccedi.isEnabled = true; bGoogle.isEnabled = true; bAccedi.text = getString(R.string.btn_signin)
                    scegliLibreria(repos)
                }
            } catch (e: Exception) {
                ui.post { if (!vivo()) return@post;
                    bAccedi.text = getString(R.string.btn_signin); bGoogle.isEnabled = true
                    MaterialAlertDialogBuilder(this).setTitle(getString(R.string.login_failed_title))
                        .setMessage(getString(R.string.login_failed_msg, e.message))
                        .setPositiveButton(getString(R.string.ok), null).show()
                    ui.postDelayed({ bAccedi.isEnabled = true }, 60_000)
                }
            }
        }
    }

    /** Scelta della libreria dopo un accesso riuscito (con password o con SSO). */
    private fun scegliLibreria(repos: List<Libreria>) {
        if (repos.isEmpty()) {   // solo messaggio: con un elenco vuoto Android mostrerebbe un dialogo senza scelte
            MaterialAlertDialogBuilder(this).setTitle(getString(R.string.library_none_title))
                .setMessage(getString(R.string.library_none_msg))
                .setPositiveButton(getString(R.string.ok), null).show()
            return
        }
        // in questo dialogo con elenco niente setMessage (vedi la trappola dei dialoghi); di chi è ogni libreria sta nella riga stessa
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.library_title))
            .setItems(repos.map { etichetta(it) }.toTypedArray()) { _, i ->
                if (repos[i].origine == Origine.PROPRIA) usaLibreria(repos[i]) else confermaLibreriaAltrui(repos[i], repos)
            }.setCancelable(false).show()
    }

    /** Il nome della libreria come si legge nell'elenco e nelle schermate: per quelle non proprie dice sempre di chi sono. */
    private fun etichetta(l: Libreria) = when (l.origine) {
        Origine.PROPRIA -> l.nome
        Origine.CONDIVISA -> getString(R.string.library_item_shared, l.nome, l.di)
        Origine.GRUPPO -> getString(R.string.library_item_group, l.nome, l.di)
    }

    /** Una libreria che non è dell'utente si usa solo dopo una conferma esplicita: le foto finirebbero nello spazio di un'altra persona o di un gruppo. */
    private fun confermaLibreriaAltrui(l: Libreria, repos: List<Libreria>) {
        val testo = if (l.origine == Origine.GRUPPO) getString(R.string.library_other_msg_group, l.nome, l.di)
            else getString(R.string.library_other_msg_shared, l.nome, l.di)
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.library_other_title))
            .setMessage(testo)
            .setPositiveButton(getString(R.string.library_other_yes)) { _, _ -> usaLibreria(l) }
            .setNegativeButton(getString(R.string.library_other_back)) { _, _ -> scegliLibreria(repos) }
            .setCancelable(false).show()
    }

    private fun usaLibreria(l: Libreria) {
        if (p.repoId != l.id) p.riconciliato = false
        p.repoId = l.id; p.repoName = etichetta(l)
        messaggio(getString(R.string.signed_in))
        mostra()
    }

    /** "Accedi con Google": la schermata con la WebView ottiene il token per dispositivo dal server; le credenziali non passano mai da qui. */
    private fun accediConSso() {
        if (!salvaAccesso()) return
        if (p.url.isEmpty()) { messaggio(getString(R.string.enter_url)); return }
        sso.launch(Intent(this, SsoActivity::class.java).putExtra(SsoActivity.EXTRA_URL, p.url))
    }

    /** Il token è già salvato da SsoActivity: stessa verifica dell'accesso con password (elenco delle librerie), poi la scelta della libreria. */
    private fun dopoSso() {
        val indirizzo = p.url; val token = p.token
        utente.setText(p.user)
        bAccedi.isEnabled = false; bGoogle.isEnabled = false; bGoogle.text = getString(R.string.signing_in)
        thread {
            try {
                val repos = SeafileClient(indirizzo, token).repos()
                ui.post { if (!vivo()) return@post;
                    bAccedi.isEnabled = true; bGoogle.isEnabled = true; bGoogle.text = getString(R.string.btn_signin_google)
                    scegliLibreria(repos)
                }
            } catch (e: Exception) {
                // un token che non funziona non deve restare né sul telefono né tra i dispositivi collegati
                try { SeafileClient(indirizzo, token).logout() } catch (_: Exception) { /* già revocato o server non raggiungibile */ }
                ui.post { if (!vivo()) return@post;
                    if (p.token == token) p.token = ""
                    bAccedi.isEnabled = true; bGoogle.isEnabled = true; bGoogle.text = getString(R.string.btn_signin_google)
                    MaterialAlertDialogBuilder(this).setTitle(getString(R.string.login_failed_title))
                        .setMessage(getString(R.string.sso_failed_msg, e.message))
                        .setPositiveButton(getString(R.string.ok), null).show()
                }
            }
        }
    }

    private fun esci() {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.exit_title))
            .setMessage(getString(R.string.exit_msg))
            .setPositiveButton(getString(R.string.exit_yes)) { _, _ ->
                val indirizzo = p.url; val token = p.token
                // si chiede al server di revocare il token; se non risponde resta la revoca a mano dal sito (lo dice il testo del dialogo)
                thread { try { SeafileClient(indirizzo, token).logout() } catch (_: Exception) { /* meglio ancora: già revocato */ } }
                Pianifica.disattiva(this, p); p.azzeraAccesso(); messaggio(getString(R.string.exit_done)); mostra()
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun caricaOra() {
        if (!p.configurato) { messaggio(getString(R.string.signin_first)); return }
        premutoMs = System.currentTimeMillis()
        Pianifica.ora(this, p)
        messaggio(getString(R.string.upload_started))
        mostra()
    }

    /** Se il lavoro sta girando si ferma al termine del pezzo in corso; se è solo in coda (attesa del Wi-Fi) si annulla. */
    private fun interrompi() {
        if (inCorso()) {
            p.interrompi = true
            messaggio(getString(R.string.stop_requested))
        } else {
            WorkManager.getInstance(this).cancelUniqueWork(Pianifica.LAVORO_ORA)
            premutoMs = 0
            messaggio(getString(R.string.start_cancelled))
        }
        mostra()
    }

    private fun rifaiConfronto() {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.redo_title))
            .setMessage(getString(R.string.redo_msg))
            .setPositiveButton(getString(R.string.redo_yes)) { _, _ ->
                p.riconciliato = false; premutoMs = System.currentTimeMillis(); Pianifica.ora(this, p)
                messaggio(getString(R.string.redo_toast)); mostra()
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // attenzione: in un dialogo con un elenco di scelte NON si usa setMessage(), perché Android mostrerebbe solo il messaggio

    private fun scegliFrequenza() {
        val valori = intArrayOf(15, 60, 360, 1440, 10080)
        val nomi = arrayOf(getString(R.string.freq_opt_15m), getString(R.string.freq_opt_1h), getString(R.string.freq_opt_6h), getString(R.string.freq_opt_1d), getString(R.string.freq_opt_1w))
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.freq_title))
            .setSingleChoiceItems(nomi, valori.indexOf(p.intervalloMin).coerceAtLeast(0)) { d, i ->
                p.intervalloMin = valori[i]
                if (p.automatico) Pianifica.attiva(this, p)
                messaggio(getString(R.string.freq_toast, nomeFrequenza(p.intervalloMin)))
                d.dismiss(); mostra()
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun scegliTipi() {
        val valori = arrayOf("video", "foto", "entrambi")
        val nomi = arrayOf(getString(R.string.types_videos), getString(R.string.types_photos), getString(R.string.types_both))
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.types_title))
            .setSingleChoiceItems(nomi, valori.indexOf(p.tipi).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (valori[i] == p.tipi) return@setSingleChoiceItems
                val conFoto = valori[i] != "video"
                if (conFoto && !p.conFoto) {
                    MaterialAlertDialogBuilder(this).setTitle(getString(R.string.types_photos_title))
                        .setMessage(getString(R.string.types_photos_msg))
                        .setPositiveButton(getString(R.string.types_photos_yes)) { _, _ -> applicaTipi(valori[i]) }
                        .setNegativeButton(getString(R.string.cancel), null).show()
                } else applicaTipi(valori[i])
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun applicaTipi(nuovo: String) {
        p.tipi = nuovo
        p.riconciliato = false
        chiediPermessiIniziali()
        if (p.automatico) Pianifica.attiva(this, p)
        messaggio(getString(R.string.types_applied, nomeTipi().lowercase(lingua())))
        mostra()
    }

    private fun scegliRaggruppamento() {
        val valori = arrayOf("nessuno", "anno", "annomese")
        val oggi = Date()
        val nomi = arrayOf(
            getString(R.string.group_none),
            getString(R.string.group_year, SimpleDateFormat("yyyy", Locale.US).format(oggi)),
            getString(R.string.group_month, SimpleDateFormat("yyyy/MM", Locale.US).format(oggi)),
        )
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.group_title))
            .setSingleChoiceItems(nomi, valori.indexOf(p.raggruppa).coerceAtLeast(0)) { d, i ->
                p.raggruppa = valori[i]
                messaggio(getString(R.string.group_toast, nomi[i].substringBefore(" (").substringBefore(" e.g.").substringBefore(" es.")))
                d.dismiss(); mostra()
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun scegliLingua() {
        val valori = arrayOf(Lingua.AUTO, "en", "it")
        val nomi = arrayOf(getString(R.string.lang_auto), getString(R.string.lang_en), getString(R.string.lang_it))
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.lang_title))
            .setSingleChoiceItems(nomi, valori.indexOf(p.lingua).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (valori[i] == p.lingua) return@setSingleChoiceItems
                p.lingua = valori[i]
                Lingua.applica(this)   // AppCompat ricrea le schermate con la nuova lingua
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    /** Elenco degli album del telefono con i file dei tipi scelti, da spuntare. */
    private fun scegliAlbum() {
        val album = Media.album(this, p.conFoto, p.conVideo)
        if (album.isEmpty()) { messaggio(getString(R.string.album_no_files)); return }
        val scelti = p.albumLista.toMutableSet()
        val etichette = album.map { (nome, foto, video) ->
            val parti = listOfNotNull(
                if (p.conFoto && foto > 0) getString(R.string.album_count_photos, foto) else null,
                if (p.conVideo && video > 0) getString(R.string.album_count_videos, video) else null,
            )
            "$nome  (${parti.joinToString(", ")})"
        }.toTypedArray()
        val spuntati = BooleanArray(album.size) { album[it].first in scelti }
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.album_title))
            .setMultiChoiceItems(etichette, spuntati) { _, i, spuntato -> if (spuntato) scelti.add(album[i].first) else scelti.remove(album[i].first) }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val nuovo = album.map { it.first }.filter { it in scelti }.joinToString(",")
                if (nuovo != p.albumLista.joinToString(",")) p.riconciliato = false
                p.album = nuovo; messaggio(getString(R.string.album_saved)); mostra()
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    /** Sfoglia le cartelle della libreria, un livello alla volta. */
    private fun sfogliaCartella(dir: String) {
        if (!p.configurato) { messaggio(getString(R.string.signin_first)); return }
        thread {
            try {
                val sotto = SeafileClient(p.url, p.token).cartelle(p.repoId, dir)
                ui.post { if (!vivo()) return@post; dialogoCartelle(dir, sotto) }
            } catch (e: Exception) {
                ui.post { if (!vivo()) return@post; MaterialAlertDialogBuilder(this).setTitle(getString(R.string.folder_list_failed)).setMessage(e.message).setPositiveButton(getString(R.string.ok), null).show() }
            }
        }
    }

    private fun dialogoCartelle(dir: String, sotto: List<String>) {
        val voci = ArrayList<String>()
        voci.add(getString(R.string.folder_use_this))
        if (dir != "/") voci.add(getString(R.string.folder_parent))
        sotto.forEach { voci.add("›  $it") }
        MaterialAlertDialogBuilder(this).setTitle("${p.repoName}: $dir")
            .setItems(voci.toTypedArray()) { _, i ->
                when {
                    i == 0 -> { if (dir != p.remoteDir) p.riconciliato = false; p.remoteDir = dir; messaggio(getString(R.string.folder_saved, dir)); mostra() }
                    dir != "/" && i == 1 -> sfogliaCartella(dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" })
                    else -> sfogliaCartella(dir.trimEnd('/') + "/" + sotto[i - (if (dir != "/") 2 else 1)])
                }
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // ---------------------------------------------------------------- registro: salvataggio e ripresa

    private fun esportaIn(uri: Uri) {
        thread {
            try {
                val righe = db.esporta()
                val json = JSONObject().apply {
                    put("tipo", "seafile-big-upload-registro"); put("formato", 1)
                    put("esportatoIl", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                    put("libreria", p.repoName); put("cartella", p.remoteDir); put("album", p.album); put("tipi", p.tipi); put("sottocartelle", p.raggruppa)
                    // anche le impostazioni, così dopo una reinstallazione non si rifanno a mano (il token di accesso invece no: si rifà l'accesso)
                    put("impostazioni", JSONObject().apply {
                        put("cartella", p.remoteDir); put("album", p.album); put("tipi", p.tipi); put("sottocartelle", p.raggruppa)
                        put("intervalloMin", p.intervalloMin); put("soloWifi", p.soloWifi); put("notifiche", p.notifiche); put("lingua", p.lingua)
                    })
                    put("righe", righe)
                }
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                ui.post { if (!vivo()) return@post; messaggio(getString(R.string.export_done, righe.length())) }
            } catch (e: Exception) {
                ui.post { if (!vivo()) return@post; MaterialAlertDialogBuilder(this).setTitle(getString(R.string.export_failed)).setMessage(e.message).setPositiveButton(getString(R.string.ok), null).show() }
            }
        }
    }

    /** Rimette le impostazioni salvate nel file (solo valori ammessi; quelle mancanti restano come sono). */
    private fun applicaImpostazioni(o: JSONObject) {
        o.optString("cartella").takeIf { it.isNotEmpty() }?.let { p.remoteDir = it }
        if (o.has("album")) p.album = o.optString("album")
        o.optString("tipi").takeIf { it in listOf("video", "foto", "entrambi") }?.let { p.tipi = it }
        o.optString("sottocartelle").takeIf { it in listOf("nessuno", "anno", "annomese") }?.let { p.raggruppa = it }
        o.optInt("intervalloMin", 0).takeIf { it in listOf(15, 60, 360, 1440, 10080) }?.let { p.intervalloMin = it }
        if (o.has("soloWifi")) p.soloWifi = o.optBoolean("soloWifi", true)
        if (o.has("notifiche")) p.notifiche = o.optBoolean("notifiche", true)
        o.optString("lingua").takeIf { it in listOf("auto", "en", "it") }?.let { p.lingua = it }
        if (p.automatico && p.configurato) Pianifica.attiva(this, p)
    }

    private fun importaDa(uri: Uri) {
        thread {
            try {
                val testo = contentResolver.openInputStream(uri)!!.use { flusso ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var tot = 0
                    while (true) {
                        val n = flusso.read(buf); if (n < 0) break
                        tot += n; if (tot > 50 * 1024 * 1024) throw IllegalArgumentException(getString(R.string.import_too_big))
                        out.write(buf, 0, n)
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                val json = JSONObject(testo)
                if (json.optString("tipo") != "seafile-big-upload-registro") throw IllegalArgumentException(getString(R.string.import_invalid))
                val righe = json.getJSONArray("righe")
                ui.post { if (!vivo()) return@post;
                    MaterialAlertDialogBuilder(this).setTitle(getString(R.string.import_title))
                        .setMessage(getString(R.string.import_msg, righe.length(), json.optString("libreria"), json.optString("cartella")))
                        .setPositiveButton(getString(R.string.import_yes)) { _, _ ->
                            thread {
                                val n = db.importa(righe)
                                json.optJSONObject("impostazioni")?.let { applicaImpostazioni(it) }
                                p.riconciliato = false
                                ui.post { if (!vivo()) return@post;
                                    messaggio(getString(R.string.import_done, n)); mostra()
                                    // le impostazioni riprese possono richiedere permessi nuovi (per esempio le foto): si chiedono subito
                                    chiediPermessiIniziali()
                                    if (json.has("impostazioni")) Lingua.applica(this)   // se la lingua è cambiata, le schermate si ricreano
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel), null).show()
                }
            } catch (e: Exception) {
                ui.post { if (!vivo()) return@post; MaterialAlertDialogBuilder(this).setTitle(getString(R.string.import_failed)).setMessage(e.message).setPositiveButton(getString(R.string.ok), null).show() }
            }
        }
    }
}
