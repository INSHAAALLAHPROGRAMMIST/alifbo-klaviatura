package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tarjima tillarini tanlaş paneli (emoji paneli kabi klaviatura o'rnida, bir xil balandlikda):
 * yuqorida — nima tilda yozilayotgani (klaviatura tili), pastda — qaysi tilga tarjima qilinişi.
 * Katta tugmalar, bir bosişda tanlanadi va panel yopiladi.
 */
class TrLangPanel(
    context: Context,
    private val onBack: () -> Unit,
    private val onSource: (Int) -> Unit,
    private val onTarget: (String) -> Unit,
) : LinearLayout(context) {
    private var theme: KbTheme = Themes.light
    private var sources: List<Lang> = emptyList()
    private var sourceIdx = 0
    private var targetCode = "en"

    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context)
    private val abc = BarButton(context, "ABC", { onBack() })

    init {
        orientation = VERTICAL
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(abc, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        bar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 6.4f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46f).toInt()))
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.background)
        abc.theme = t
        render()
    }

    fun setState(sources: List<Lang>, sourceIdx: Int, targetCode: String) {
        this.sources = sources
        this.sourceIdx = sourceIdx
        this.targetCode = targetCode
        scroll.scrollTo(0, 0)
        render()
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r)
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.hint)
        setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(2f).toInt())
    }

    /** [items] ni [cols] ustunli to'r qilib joylaydi; har biri (nom, tanlanganmi, bosilganda). */
    private fun grid(items: List<Triple<String, Boolean, () -> Unit>>, cols: Int): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
        }
        for (rowItems in items.chunked(cols)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((name, selected, tap) in rowItems) {
                val chip = TextView(context).apply {
                    text = name
                    textSize = 15f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    background = rounded(if (selected) theme.accent else theme.key, 10f)
                    setTextColor(if (selected) theme.accentText else theme.text)
                    setOnClickListener { tap() }
                }
                row.addView(chip, LayoutParams(0, dp(42f).toInt(), 1f).apply {
                    setMargins(dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt())
                })
            }
            // oxirgi qator to'lmasa, ustunlar kengligi bir xil qolsin
            for (k in rowItems.size until cols) {
                row.addView(View(context), LayoutParams(0, dp(42f).toInt(), 1f).apply {
                    setMargins(dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt())
                })
            }
            box.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        return box
    }

    private fun render() {
        content.removeAllViews()
        content.addView(label("Nima tilda yozasiz (klaviatura tili)"))
        content.addView(
            grid(sources.mapIndexed { i, l -> Triple(l.label, i == sourceIdx) { onSource(i) } }, 4),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        content.addView(label("Qaysi tilga tarjima qilinsin"))
        content.addView(
            grid(
                Translator.targets.map { (code, _) ->
                    Triple(Translator.displayName(code), code == targetCode) { onTarget(code) }
                },
                4,
            ),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }
}
