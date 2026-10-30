package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Parollar paneli tugmalari (klaviatura xizmati amalga oshiradi). */
interface VaultHandler {
    fun onVaultFill(c: Cred)
    fun onVaultDelete(c: Cred)
    fun onVaultUseGenerated(password: String)
    fun onVaultRegenerate()
    fun onVaultUnlock()
    fun onVaultLock()
}

/** Panel holati: ombor ochiqmi, shu ilova va boshqa ilovalar uchun kirishlar, taklif qilingan kuchli parol. */
data class VaultState(
    val open: Boolean,
    val mine: List<Cred>,
    val others: List<Cred>,
    val generated: String,
    val note: String? = null,
)

/**
 * Parollar paneli (klaviatura o'rnida): yuqorida yangi kuchli parol taklifi, keyin saqlangan kirishlar —
 * bosilsa maydonga yoziladi (login maydoniga foydalanuvchi nomi, parol maydoniga parol).
 * Parollarning o'zi ko'rsatilmaydi. ✕ ikki bosqichli: avval so'raydi, keyin o'chiradi.
 */
class VaultPanel(context: Context, private val onBack: () -> Unit) : LinearLayout(context) {
    var vaultHandler: VaultHandler? = null
    private var theme: KbTheme = Themes.light
    private var state = VaultState(false, emptyList(), emptyList(), "")
    private var confirmDelete: String? = null

    private val title = TextView(context)
    private val lockAction = TextView(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context)
    private val abc = BarButton(context, "ABC", { onBack() })

    init {
        orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), 0, dpi(8f), 0)
        }
        title.text = "Parollar"
        title.textSize = 15f
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        lockAction.textSize = 14f
        lockAction.setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
        lockAction.setOnClickListener {
            if (state.open) vaultHandler?.onVaultLock() else vaultHandler?.onVaultUnlock()
        }
        header.addView(lockAction)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dpi(40f)))

        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(abc, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.4f))
        bar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 6.4f))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(46f)))
    }

    private fun dpi(v: Float): Int = dp(v).toInt()

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r)
    }

    fun applyTheme(t: KbTheme) {
        theme = t
        setBackgroundColor(t.background)
        title.setTextColor(t.text)
        lockAction.setTextColor(t.accent)
        abc.theme = t
        render()
    }

    fun setState(s: VaultState) {
        if (s.open != state.open) confirmDelete = null
        state = s
        render()
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun margins() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        setMargins(dpi(8f), dpi(4f), dpi(8f), dpi(4f))
    }

    private fun render() {
        val t = theme
        lockAction.text = if (state.open) "Qulflaş" else "Oçiş"
        list.removeAllViews()

        // yangi kuchli parol
        val gen = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(t.key, 10f)
            setPadding(dpi(12f), dpi(8f), dpi(4f), dpi(8f))
            setOnClickListener { vaultHandler?.onVaultUseGenerated(state.generated) }
        }
        val genText = LinearLayout(context).apply { orientation = VERTICAL }
        genText.addView(text("Kuçli parol (bosing — yoziladi)", 12f, t.hint))
        genText.addView(text(state.generated, 16f, t.accent).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        gen.addView(genText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        gen.addView(text("↻", 20f, t.textSpecial).apply {
            setPadding(dpi(12f), dpi(4f), dpi(12f), dpi(4f))
            setOnClickListener { vaultHandler?.onVaultRegenerate() }
        })
        list.addView(gen, margins())

        state.note?.let { list.addView(text(it, 13f, t.hint).apply { setPadding(dpi(16f), dpi(6f), dpi(16f), dpi(2f)) }) }

        if (!state.open) {
            val locked = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpi(16f), dpi(18f), dpi(16f), dpi(18f))
            }
            locked.addView(
                text("Saqlangan parollar şifrlangan.\nKörish uçun telefon qulfi bilan oçing.", 14f, t.hint).apply {
                    gravity = Gravity.CENTER
                },
            )
            locked.addView(
                text("Oçiş", 15f, t.accentText, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(t.accent, 12f)
                    setPadding(dpi(24f), dpi(9f), dpi(24f), dpi(9f))
                    setOnClickListener { vaultHandler?.onVaultUnlock() }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) },
            )
            list.addView(locked, margins())
            return
        }

        if (state.mine.isEmpty() && state.others.isEmpty()) {
            list.addView(
                text(
                    "Hali parol saqlanmagan.\nBiror ilovaga kirganingizda Söz+ parolni şifrlab eslab qoladi.",
                    14f, t.hint,
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(dpi(16f), dpi(22f), dpi(16f), dpi(22f))
                },
                margins(),
            )
            return
        }
        if (state.mine.isNotEmpty()) section("Şu ilova", state.mine)
        if (state.others.isNotEmpty()) section("Boşqa ilovalar", state.others)
    }

    private fun section(name: String, items: List<Cred>) {
        val t = theme
        list.addView(text(name.uppercase(), 11f, t.hint, bold = true).apply {
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(2f))
        })
        for (c in items) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(t.key, 10f)
                setPadding(dpi(12f), dpi(8f), dpi(4f), dpi(8f))
                setOnClickListener { vaultHandler?.onVaultFill(c) }
            }
            val body = LinearLayout(context).apply { orientation = VERTICAL }
            body.addView(text(c.user.ifEmpty { "(foydalanuvçi nomisiz)" }, 15f, t.text).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            body.addView(text("${c.label.ifEmpty { c.app }}  ·  ••••••••", 12f, t.hint).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val asking = confirmDelete == c.id
            row.addView(
                text(if (asking) "Öçiriş" else "✕", if (asking) 13f else 15f, if (asking) 0xFFE5484D.toInt() else t.hint, bold = asking).apply {
                    setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f))
                    setOnClickListener {
                        if (confirmDelete == c.id) {
                            confirmDelete = null
                            vaultHandler?.onVaultDelete(c)
                        } else {
                            confirmDelete = c.id
                            render()
                        }
                    }
                },
            )
            list.addView(row, margins())
        }
    }
}
