package dev.seafilebigupload

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Date

/** L'elenco dei file del registro con lo stato di ciascuno, filtrabile. */
class ElencoActivity : AppCompatActivity() {
    private lateinit var db: Db
    private lateinit var lista: ListView
    private lateinit var vuoto: TextView
    private var tutti: List<Db.Riga> = emptyList()
    private var filtro = "tutti"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        db = Db(this)
        val radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applicaInsets(radice)
        radice.addView(MaterialToolbar(this).apply {
            title = getString(R.string.list_title)
            setTitleTextColor(Ui.testo(context))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))

        val gruppo = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0) }
        val voci = listOf(
            "tutti" to getString(R.string.list_all), Db.Stato.DA_CARICARE to getString(R.string.list_waiting),
            Db.Stato.FATTO to getString(R.string.list_done), Db.Stato.FATTO_SERVER to getString(R.string.list_server),
        )
        for ((chiave, testo) in voci) {
            val chip = Chip(this).apply {
                text = testo; isCheckable = true; isChecked = chiave == filtro
                setOnClickListener { filtro = chiave; mostra() }
            }
            gruppo.addView(chip)
        }
        radice.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(gruppo) })

        vuoto = TextView(this).apply {
            text = getString(R.string.list_empty); setTextColor(Ui.grigio(context)); gravity = android.view.Gravity.CENTER
            setPadding(Ui.dp(context, 20), Ui.dp(context, 40), Ui.dp(context, 20), 0)
        }
        lista = ListView(this).apply { divider = null; emptyView = null }
        radice.addView(lista, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        radice.addView(vuoto)
        setContentView(radice)
    }

    override fun onResume() { super.onResume(); mostra() }
    override fun onDestroy() { super.onDestroy(); db.close() }

    private fun etichetta(r: Db.Riga) = when (r.stato) {
        Db.Stato.FATTO -> getString(R.string.label_done)
        Db.Stato.FATTO_SERVER -> getString(R.string.label_server)
        Db.Stato.ASSENTE -> getString(R.string.label_absent)
        else -> if (r.errore.isEmpty()) getString(R.string.label_waiting) else getString(R.string.label_retry, r.tentativi, r.errore.take(120))
    }

    private fun mostra() {
        tutti = db.tutti().filter { it.stato != Db.Stato.ASSENTE }
        val righe = tutti.filter { filtro == "tutti" || it.stato == filtro }
            .sortedWith(compareBy({ if (it.stato == Db.Stato.DA_CARICARE) 0 else 1 }, { -it.aggiornatoMs }))
        lista.adapter = Adattatore(righe)
        vuoto.visibility = if (righe.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adattatore(val righe: List<Db.Riga>) : BaseAdapter() {
        override fun getCount() = righe.size
        override fun getItem(i: Int) = righe[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, vista: View?, parent: ViewGroup): View {
            val r = righe[i]
            val ctx = this@ElencoActivity
            val nome = TextView(ctx).apply { text = r.nome; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(Ui.testo(ctx)) }
            val dettaglio = TextView(ctx).apply {
                text = "${getString(if (r.tipo == Media.FOTO) R.string.kind_photo else R.string.kind_video)} · ${Caricatore.mb(r.dimensione)} · ${etichetta(r)}" +
                    (if (r.stato == Db.Stato.FATTO) " · ${SimpleDateFormat("dd/MM HH:mm", Lingua.locale(ctx)).format(Date(r.aggiornatoMs))}" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (r.stato == Db.Stato.DA_CARICARE && r.errore.isNotEmpty()) Ui.rosso(ctx) else Ui.grigio(ctx))
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 10), Ui.dp(ctx, 20), Ui.dp(ctx, 10))
                addView(nome); addView(dettaglio)
            }
        }
    }
}
