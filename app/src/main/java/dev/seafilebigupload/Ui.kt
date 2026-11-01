package dev.seafilebigupload

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as M

/** Pezzi di grafica condivisi dalle schermate: righe come nelle impostazioni dell'app Seafile (titolo, spiegazione, interruttore). */
object Ui {
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun colore(ctx: Context, attr: Int, predefinito: Int = Color.GRAY) = MaterialColors.getColor(ctx, attr, predefinito)

    fun notte(ctx: Context) = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun verde(ctx: Context) = ctx.getColor(if (notte(ctx)) R.color.stato_ok_notte else R.color.stato_ok)
    fun rosso(ctx: Context) = ctx.getColor(if (notte(ctx)) R.color.stato_errore_notte else R.color.stato_errore)
    fun arancione(ctx: Context) = colore(ctx, M.attr.colorPrimary)
    fun grigio(ctx: Context) = colore(ctx, M.attr.colorOnSurfaceVariant)
    fun testo(ctx: Context) = colore(ctx, M.attr.colorOnSurface)

    /** Titolo di sezione: arancione, piccolo, in grassetto. */
    fun sezione(ctx: Context, testo: String) = TextView(ctx).apply {
        text = testo
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(arancione(ctx))
        setPadding(dp(ctx, 20), dp(ctx, 24), dp(ctx, 20), dp(ctx, 4))
    }

    class Riga(val vista: LinearLayout, val titolo: TextView, val sommario: TextView, val interruttore: MaterialSwitch?) {
        fun abilita(si: Boolean) {
            vista.isEnabled = si; interruttore?.isEnabled = si
            vista.alpha = if (si) 1f else 0.45f
        }
    }

    /** Una riga di impostazione. Con `conInterruttore` tutta la riga è un interruttore. */
    fun riga(ctx: Context, titolo: String, sommario: String, conInterruttore: Boolean = false, onClick: (() -> Unit)? = null): Riga {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        val t = TextView(ctx).apply {
            text = titolo
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(testo(ctx))
        }
        val s = TextView(ctx).apply {
            text = sommario
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(grigio(ctx))
            setPadding(0, dp(ctx, 2), 0, 0)
        }
        val colonna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(t); addView(s)
        }
        val sw = if (conInterruttore) MaterialSwitch(ctx).apply { setPadding(dp(ctx, 8), 0, 0, 0) } else null
        val riga = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
            setBackgroundResource(tv.resourceId)
            isClickable = true; isFocusable = true
            addView(colonna, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (sw != null) addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { if (sw != null) sw.toggle() else onClick?.invoke() }
        }
        return Riga(riga, t, s, sw)
    }

    /** Spazio per la barra di stato e quella dei gesti (le app che puntano ad Android 15 disegnano sotto). */
    fun applicaInsets(radice: View) {
        ViewCompat.setOnApplyWindowInsetsListener(radice) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }
}
