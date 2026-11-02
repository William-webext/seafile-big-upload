package dev.seafilebigupload

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Accesso con SSO (Google o altro): apre `/shib-login` del server in una WebView e aspetta che il server scriva il token
 * nel cookie `seahub_auth`. Se arriva, lo salva (cifrato) in `Prefs` e chiude con RESULT_OK; chiusura, annullamento o errore
 * danno RESULT_CANCELED e non lasciano niente. Non salva né vede mai una password: la digita l'utente sulla pagina dell'identity provider.
 */
class SsoActivity : AppCompatActivity() {
    private lateinit var p: Prefs
    private lateinit var web: WebView
    private lateinit var barra: LinearProgressIndicator
    private lateinit var base: String
    private var finito = false

    @SuppressLint("SetJavaScriptEnabled")   // le pagine di Google e di Seafile non funzionano senza JavaScript
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        p = Prefs(this)
        base = intent.getStringExtra(EXTRA_URL).orEmpty().trim().trimEnd('/')
        if (!base.startsWith("https://")) { finish(); return }   // l'indirizzo lo controlla già la schermata principale

        val ctx = this
        val radice = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        Ui.applicaInsets(radice)
        radice.addView(MaterialToolbar(ctx).apply {
            title = getString(R.string.sso_title)
            setTitleTextColor(Ui.testo(context))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { annulla() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 56)))
        barra = LinearProgressIndicator(ctx).apply { isIndeterminate = true }
        radice.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        web = WebView(ctx)
        radice.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = Client()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else annulla() }
        })

        // si parte da cookie vuoti: un seahub_auth rimasto da un accesso precedente non deve valere come risposta di questo
        val indirizzo = Sso.indirizzoAccesso(base, p.deviceId, "Seafile Big Upload (${Build.MODEL})", SeafileClient.CLIENT_VERSION, Build.VERSION.RELEASE)
        CookieManager.getInstance().removeAllCookies { if (!isFinishing && !finito) web.loadUrl(indirizzo) }
    }

    /** Se il server ha già scritto il token nel cookie, lo salva e chiude. Si chiama a ogni passo della navigazione: il cookie arriva con una redirect. */
    private fun controllaCookie() {
        if (finito) return
        val (email, token) = Sso.leggiCookie(CookieManager.getInstance().getCookie(base)) ?: return
        finito = true
        p.token = token; p.user = email
        pulisci()
        setResult(RESULT_OK)
        finish()
    }

    private fun annulla() {
        if (finito) return
        finito = true
        pulisci()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun errore(testo: String) {
        if (finito) return
        finito = true
        pulisci()
        web.stopLoading()
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.sso_error_title))
            .setMessage(testo)
            .setPositiveButton(getString(R.string.ok), null)
            .setOnDismissListener { setResult(RESULT_CANCELED); finish() }
            .show()
    }

    /** I cookie dell'accesso (anche quelli di Google) non devono restare nella WebView. */
    private fun pulisci() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            if (!finito) pulisci()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.scheme != "https"   // niente http né schemi di altre app: si resta su connessioni cifrate

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            barra.visibility = View.VISIBLE; controllaCookie()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = controllaCookie()

        override fun onPageFinished(view: WebView, url: String?) {
            barra.visibility = View.GONE; controllaCookie()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) errore(getString(R.string.sso_error_load, error.description, request.url.host.orEmpty()))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            // la pagina d'errore di Seafile (accesso non consentito, utente non abilitato…) arriva con un codice 4xx/5xx
            // solo se il cookie non è già arrivato con la redirect
            if (!request.isForMainFrame || errorResponse.statusCode < 400) return
            controllaCookie()
            if (!finito) errore(getString(R.string.sso_error_http, errorResponse.statusCode, request.url.host.orEmpty()))
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()   // mai proseguire con un certificato non valido
            errore(getString(R.string.sso_error_ssl, error.url.toHttpHost()))
        }
    }

    private fun String?.toHttpHost(): String = this?.removePrefix("https://")?.substringBefore('/').orEmpty()

    companion object {
        const val EXTRA_URL = "url"
    }
}
