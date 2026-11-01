package dev.seafilebigupload

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar

/** Informazioni sull'app: versione, ideatore, codice sorgente, avvertenze. */
class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctx = this
        val radice = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        Ui.applicaInsets(radice)
        radice.addView(MaterialToolbar(ctx).apply {
            title = getString(R.string.about_title)
            setTitleTextColor(Ui.testo(context))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 56)))

        val info = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        @Suppress("DEPRECATION")
        val versione = getString(R.string.about_version, "${info?.versionName ?: "?"}" + (info?.let { " (${it.versionCode})" } ?: ""))

        val colonna = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, Ui.dp(ctx, 32)) }
        colonna.addView(ImageView(ctx).apply { setImageDrawable(ctx.getDrawable(R.mipmap.ic_launcher)) },
            LinearLayout.LayoutParams(Ui.dp(ctx, 88), Ui.dp(ctx, 88)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(ctx, 16) })
        colonna.addView(TextView(ctx).apply {
            text = getString(R.string.app_name); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Ui.testo(ctx)); gravity = Gravity.CENTER
            setPadding(0, Ui.dp(ctx, 12), 0, 0)
        })
        colonna.addView(TextView(ctx).apply {
            text = versione; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Ui.grigio(ctx)); gravity = Gravity.CENTER
        })
        colonna.addView(TextView(ctx).apply {
            text = getString(R.string.about_intro)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(Ui.grigio(ctx)); gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 28), Ui.dp(ctx, 12), Ui.dp(ctx, 28), 0)
        })

        colonna.addView(Ui.sezione(ctx, getString(R.string.about_sec_creator)))
        colonna.addView(Ui.riga(ctx, "William Baglioni", getString(R.string.about_creator_summary)).vista)

        colonna.addView(Ui.sezione(ctx, getString(R.string.about_sec_source)))
        colonna.addView(Ui.riga(ctx, getString(R.string.about_repo), REPOSITORY) { apri(REPOSITORY) }.vista)
        colonna.addView(Ui.riga(ctx, getString(R.string.about_issues), "$REPOSITORY/issues") { apri("$REPOSITORY/issues") }.vista)

        colonna.addView(Ui.sezione(ctx, getString(R.string.about_sec_privacy)))
        colonna.addView(Ui.riga(ctx, getString(R.string.about_privacy_title), getString(R.string.about_privacy_text)).vista)

        colonna.addView(Ui.sezione(ctx, getString(R.string.about_sec_notice)))
        colonna.addView(Ui.riga(ctx, getString(R.string.about_notice_title), getString(R.string.about_notice_text)).vista)

        colonna.addView(Ui.sezione(ctx, getString(R.string.about_sec_built)))
        colonna.addView(Ui.riga(ctx, getString(R.string.about_built_title), getString(R.string.about_built_text)).vista)

        radice.addView(ScrollView(ctx).apply { addView(colonna) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(radice)
    }

    private fun apri(indirizzo: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(indirizzo))) } catch (_: Exception) { /* nessun browser installato */ }
    }

    companion object {
        const val REPOSITORY = "https://github.com/William-webext/seafile-big-upload"
    }
}
