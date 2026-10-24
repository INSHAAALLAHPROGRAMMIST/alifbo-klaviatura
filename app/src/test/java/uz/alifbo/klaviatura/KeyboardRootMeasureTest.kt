package uz.alifbo.klaviatura

import android.content.Context
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** IME oynasi butun ekranga cho'zilib ketmasligi uchun: ildiz ko'rinish faqat o'z tarkibi balandligida bo'lsin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardRootMeasureTest {

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

    @Test
    fun ildizBalandligi_ekranBalandligiga_chozilmaydi() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val root = KeyboardRoot(ctx, NoOp)
        val screenH = 2400
        root.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST),
        )
        val d = ctx.resources.displayMetrics.density
        // qator 44dp + 4 qator x 52dp + yuqori/pastki 6dp
        val expected = (44 * d).toInt() + (4 * 52 * d + 12 * d).toInt()
        assertTrue("balandlik ${root.measuredHeight} px: butun ekran emas, ~$expected bo'lishi kerak", root.measuredHeight < screenH / 2)
        assertEquals(expected.toDouble(), root.measuredHeight.toDouble(), 3.0)
    }

    @Test
    fun qatlam_AT_MOST_da_balandlik_talab_qilmaydi() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val overlay = OverlayView(ctx)
        overlay.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(2400, MeasureSpec.AT_MOST),
        )
        assertEquals(0, overlay.measuredHeight)
        overlay.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(500, MeasureSpec.EXACTLY),
        )
        assertEquals(500, overlay.measuredHeight)
    }
}
