package uz.alifbo.klaviatura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Dizaynni ko'z bilan tekshirish uchun rasmlar: klaviatura (har xil tema va til), bosilgan holat,
 * sozlamalar ekrani va ikon. build/screens/ ga yoziladi, CI ularni artefakt sifatida beradi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/screens").apply { mkdirs() }

    private object NoOp : KeyboardListener {
        override fun onText(text: String) {}
        override fun onSpace() {}
        override fun onDelete() {}
        override fun onEnter() {}
        override fun onShiftTap() {}
        override fun onShiftHold() {}
        override fun onPage(page: Page) {}
        override fun onLanguageNext() {}
        override fun onLanguagePick(index: Int) {}
        override fun onEmoji() {}
        override fun onLanguageSwipe(step: Int) {}
    }

    private fun save(name: String, v: View, width: Int, height: Int = 0) {
        v.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            if (height > 0) MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val screenW: Int get() = (411 * ctx.resources.displayMetrics.density).toInt()

    private fun root(theme: KbTheme, lang: Lang, page: Page = Page.LETTERS, shift: Shift = Shift.OFF, suggestions: List<String>): KeyboardRoot {
        val r = KeyboardRoot(ctx, NoOp)
        r.applyTheme(theme)
        val k = r.keyboard
        k.casing = page == Page.LETTERS
        k.langName = lang.label
        k.langShorts = Lang.entries.map { it.short }
        k.shift = shift
        k.setLayout(if (page == Page.LETTERS) Layouts.letters(lang) else Layouts.symbols(page))
        r.strip.suggestions = suggestions
        return r
    }

    @Test
    fun klaviaturaRasmlari() {
        val themes = listOf(
            "yorug" to Themes.light, "qora" to Themes.dark, "tun" to Themes.night, "yashil" to Themes.green,
            "qumloq" to Themes.sand, "binafsha" to Themes.purple, "dengiz" to Themes.sea,
        )
        for ((name, t) in themes) {
            save("klaviatura_$name", root(t, Lang.UZ, suggestions = listOf("yaxşi", "bugun", "juda")), screenW)
        }
        save("kiril_yorug", root(Themes.light, Lang.UZC, shift = Shift.ONCE, suggestions = listOf("Яхши", "Бугун", "Жуда")), screenW)
        save("rus_qora", root(Themes.dark, Lang.RU, suggestions = listOf("хочу", "не", "знаю")), screenW)
        save("belgilar_yorug", root(Themes.light, Lang.UZ, page = Page.SYM1, suggestions = emptyList()), screenW)
        // o'zbek tiliga optimallashtirilgan "Tezkor" maket
        val fast = root(Themes.light, Lang.UZ, suggestions = listOf("yaxşi", "bugun", "juda"))
        fast.keyboard.setLayout(Layouts.letters(Lang.UZ, uzVariant = Layouts.UZ_FAST))
        save("tezkor_yorug", fast, screenW)
        // bir qo'l (o'ng bosh barmoq) maketi
        val one = root(Themes.dark, Lang.UZ, suggestions = listOf("yaxşi", "bugun", "juda"))
        one.keyboard.setLayout(Layouts.letters(Lang.UZ, uzVariant = Layouts.UZ_ONE_HAND))
        save("bir_qol_qora", one, screenW)
        // avtomatik tuzatish kutilmoqda: o'rtada urg'u rangida, chapda yozilganning o'zi
        val fix = root(Themes.light, Lang.UZ, suggestions = listOf("yaxşi", "“yaxsi”", "yaxşilab"))
        fix.strip.highlightBest = true
        save("tuzatish_yorug", fix, screenW)
        // yozish boshida oxirgi nusxa taklifi
        val clip = root(Themes.light, Lang.UZ, suggestions = listOf("bugun", "men", "bu"))
        clip.strip.clipText = "Toşkent şahri, Amir Temur ko'çasi 15-uy\n(ikkinçi qavat)"
        save("bufer_taklifi_yorug", clip, screenW)
        // bufer paneli
        for ((name, t) in listOf("yorug" to Themes.light, "qora" to Themes.dark)) {
            val r = root(t, Lang.UZ, suggestions = emptyList())
            save("bufer_tmp", r, screenW)
            val store = ClipboardStore()
            store.add("Assalomu alaykum! Ertaga soat 10:00 da uçraşamiz.", 1000)
            store.add("https://github.com/INSHAAALLAHPROGRAMMIST/alifbo-klaviatura", 2000)
            store.add("Toşkent şahri, Amir Temur ko'çasi 15-uy", 3000)
            store.togglePin("Assalomu alaykum! Ertaga soat 10:00 da uçraşamiz.")
            store.add("+998 90 123 45 67", 4000)
            r.showClipboard(store.sorted(), object : ClipboardHandler {
                override fun onClipPaste(text: String) {}
                override fun onClipPin(text: String) {}
                override fun onClipDelete(text: String) {}
                override fun onClipClear() {}
            })
            save("bufer_paneli_$name", r, screenW)
        }
        File(outDir, "bufer_tmp.png").delete()
        save("belgilar2_qora", root(Themes.dark, Lang.UZ, page = Page.SYM2, suggestions = emptyList()), screenW)
        tarjimaVaParollar()
    }

    private object NoTr : TranslateHandler {
        override fun onTrSend() {}
        override fun onTrClose() {}
        override fun onTrLangs() {}
        override fun onTrSwap() {}
        override fun onTrPasteClip() {}
        override fun onTrStatusTap() {}
        override fun onTrCursor(position: Int) {}
    }

    private object NoEditor : EditorHandler {
        override fun onEditorDone() {}
        override fun onEditorCancel() {}
        override fun onEditorPaste() {}
        override fun onEditorCopy() {}
        override fun onEditorSend() {}
        override fun onEditorCursor(position: Int) {}
    }

    private object NoVault : VaultHandler {
        override fun onVaultFill(c: Cred) {}
        override fun onVaultDelete(c: Cred) {}
        override fun onVaultUseGenerated(password: String) {}
        override fun onVaultRegenerate() {}
        override fun onVaultUnlock() {}
        override fun onVaultLock() {}
    }

    /** Söz+ (toliq): tarjima qatori, parol maydonidagi qator va parollar paneli. */
    private fun tarjimaVaParollar() {
        val tr = root(Themes.light, Lang.UZ, suggestions = listOf("uçraşamizmi", "uçraşamiz", "uçraşuv"))
        tr.showTranslate(NoTr)
        tr.trBar.render(TrState("UZ", "EN", "Ertaga soat beşda uçraşamizmi?"))
        save("tarjima_yorug", tr, screenW)
        val trLangs = root(Themes.light, Lang.UZ, suggestions = emptyList())
        trLangs.showTranslate(NoTr)
        save("tarjima_tmp", trLangs, screenW)
        trLangs.showTrLangs(Lang.entries, 0, "en", {}, {})
        save("tarjima_tillar", trLangs, screenW)
        File(outDir, "tarjima_tmp.png").delete()
        val trDone = root(Themes.light, Lang.UZ, suggestions = emptyList())
        trDone.showTranslate(NoTr)
        trDone.trBar.render(TrState("UZ", "EN", "", status = "✓ Qöyildi · qaytariş"))
        save("tarjima_tayyor", trDone, screenW)
        val long = "Assalomu alaykum! Ertaga soat beşda uçraşamizmi?\nMen hujjatlarni ham olib boraman, siz esa " +
            "şartnomaning oxirgi nusxasini unutmang. Rahmat."
        // matn örtasidagi kursor: qatorda ham, muharrirda ham körinişi kerak
        val trMid = root(Themes.light, Lang.UZ, suggestions = emptyList())
        trMid.showTranslate(NoTr)
        trMid.strip.expandable = true
        trMid.trBar.render(TrState("UZ", "EN", long, cursor = 30))
        save("tarjima_kursor", trMid, screenW)
        for ((name, t, translate) in listOf(Triple("yorug", Themes.light, false), Triple("qora", Themes.dark, true))) {
            val r = root(t, Lang.UZ, suggestions = listOf("Rahmat", "Raqam", "Rasm"))
            if (translate) r.showTranslate(NoTr)
            r.strip.expandable = true
            save("muharrir_tmp", r, screenW)
            r.showEditor(NoEditor)
            r.editor.render(EditorState(if (translate) "UZ → EN · 150/3000" else "Tahrirlaş · 150 belgi", long, 50, translate))
            save("muharrir_$name", r, screenW)
        }
        File(outDir, "muharrir_tmp.png").delete()
        val trq = root(Themes.dark, Lang.UZ, suggestions = emptyList())
        trq.showTranslate(NoTr)
        trq.trBar.render(TrState("UZ", "RU", "Salom, qalaysiz?", busy = true, status = "Tarjima qilinmoqda…"))
        save("tarjima_qora", trq, screenW)

        val pw = root(Themes.light, Lang.UZ, suggestions = emptyList())
        pw.strip.actions = listOf(
            StripAction(1, StripAction.ICON_KEY, "alisher@mail.uz"),
            StripAction(3, StripAction.ICON_DICE, "Kuçliroq: Xk7#pQ2m!vR9aT", accent = true),
        )
        save("parol_qatori_yorug", pw, screenW)

        val mine = listOf(Cred("1", "org.telegram.messenger", "Telegram", "+998 90 123 45 67", "x", 3))
        val others = listOf(
            Cred("2", "uz.click", "Click", "alisher@mail.uz", "y", 2),
            Cred("3", "com.android.chrome", "Chrome", "alisher_t", "z", 1),
        )
        for ((name, t) in listOf("yorug" to Themes.light, "qora" to Themes.dark)) {
            val r = root(t, Lang.UZ, suggestions = emptyList())
            save("parol_tmp", r, screenW)
            r.showVault(VaultState(true, mine, others, "Xk7#pQ2m!vR9aTe4"), NoVault)
            save("parollar_paneli_$name", r, screenW)
            r.showVault(VaultState(false, emptyList(), emptyList(), "Xk7#pQ2m!vR9aTe4"), NoVault)
            save("parollar_qulfli_$name", r, screenW)
        }
        File(outDir, "parol_tmp.png").delete()
    }

    @Test
    fun bosilganHolat() {
        for ((name, t) in listOf("yorug" to Themes.light, "qora" to Themes.dark)) {
            val r = root(t, Lang.UZ, shift = Shift.CAPS, suggestions = listOf("Yaxşi", "Bugun", "Juda"))
            save("bosilgan_tmp", r, screenW)
            val k = r.keyboard
            val keyH = k.keyHeightPx
            val w = screenW / 10f
            val stripH = r.strip.height.toFloat()
            // 2-qatordagi "g" (5-tugma) bosilgandek: pufakcha
            val gx = 4 * w
            r.overlay.showPreview(
                RectF(gx + 7f, stripH + 18f + keyH + 10f, gx + w - 7f, stripH + 18f + 2 * keyH - 10f),
                "G", keyH, k.cornerPx,
            )
            save("bosilgan_$name", r, screenW)
            r.overlay.hidePreview()
            // 1-qatordagi "e" uzoq bosilgan: raqam va boshqa belgilar oynasi
            val ex = 2 * w
            val top = stripH + 18f
            r.overlay.showPopup(
                (0 until 3).map { i -> RectF(ex + i * w, top - keyH * 0.93f, ex + (i + 1) * w, top + keyH * 0.12f) },
                listOf("3", "é", "è"), 0,
            )
            save("uzoq_bosish_$name", r, screenW)
        }
        File(outDir, "bosilgan_tmp.png").delete()
    }

    @Test
    fun sozlamalarEkrani() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val scroll = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
        val content = scroll.getChildAt(0)
        // butun sahifa (aylantirilmagan holda ham)
        content.measure(
            MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val bmp = Bitmap.createBitmap(content.measuredWidth, content.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor((scroll.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0xFFF2F3F5.toInt())
        content.draw(c)
        FileOutputStream(File(outDir, "sozlamalar.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun png(name: String, size: Int, block: (Canvas) -> Unit) {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        block(Canvas(bmp))
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun ikon() {
        val size = 432
        val d = ctx.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        png("ikon", size) { c ->
            d.setBounds(0, 0, size, size)
            d.draw(c)
        }
        // telefonlardagi niqoblar: 108dp qatlamning markaziy 72dp qismi ko'rinadi
        val full = (size * 1.5f).toInt()
        val off = -(full - size) / 2
        for ((name, frac) in listOf("ikon_doira" to 0.5f, "ikon_yumaloq" to 0.3f)) {
            png(name, size) { c ->
                val p = Path().apply {
                    addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * frac, size * frac, Path.Direction.CW)
                }
                c.clipPath(p)
                d.background?.apply { setBounds(off, off, off + full, off + full); draw(c) }
                d.foreground?.apply { setBounds(off, off, off + full, off + full); draw(c) }
            }
        }
        // Android 13+ temali ikon: tizim bir rangga bo'yaydi
        png("ikon_temali", size) { c ->
            val p = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
            c.clipPath(p)
            c.drawColor(0xFFDCE4F7.toInt())
            d.monochrome?.mutate()?.apply {
                setTint(0xFF1E3A7B.toInt())
                setBounds(off, off, off + full, off + full)
                draw(c)
            }
        }
    }
}
