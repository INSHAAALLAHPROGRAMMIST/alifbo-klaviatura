package uz.alifbo.klaviatura

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Tarjima ko'prigi: klaviaturaning o'zi internetga chiqmaydi. Matn telefondagi tarjima ilovasiga
 * (avvalo Google Tarjimon) Android'ning "matnni qayta ishlash" (PROCESS_TEXT) yo'li bilan uzatiladi;
 * ilova tarjimani qaytarsa, klaviatura uni yozilgan matn o'rniga qo'yadi.
 */
object TranslateBridge {
    const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"

    /** Ma'qul ko'rilgan tarjima ilovalari (tartib bo'yicha). */
    val preferred = listOf(GOOGLE_TRANSLATE, "ru.yandex.translate", "com.deepl.mobiletranslator")

    private var callback: ((String) -> Unit)? = null

    fun request(cb: (String) -> Unit) {
        callback = cb
    }

    fun deliver(text: String) {
        callback?.invoke(text)
        callback = null
    }

    fun cancel() {
        callback = null
    }

    /** Telefondagi mos tarjima ilovasi (PROCESS_TEXT faoliyati) yoki null. */
    fun findTranslator(pm: PackageManager, self: String): ResolveInfo? {
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
        for (p in preferred) list.firstOrNull { it.activityInfo.packageName == p }?.let { return it }
        val words = listOf("translat", "tarjim", "перев")
        return list.firstOrNull { ri ->
            ri.activityInfo.packageName != self &&
                words.any { ri.loadLabel(pm).toString().lowercase().contains(it) }
        }
    }

    fun isGoogleTranslateInstalled(pm: PackageManager): Boolean = try {
        pm.getPackageInfo(GOOGLE_TRANSLATE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Play Market'da Google Tarjimon sahifasi (Play Market ilovasi orqali). */
    fun openStore(activity: android.content.Context) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TRANSLATE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(activity, "Play Market topilmadi", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Ko'rinmas oraliq oyna: tarjima ilovasini natija kutib ochadi va natijani klaviaturaga beradi. */
class TranslateActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // tarjima ilovasi ochiq, natijani kutyapmiz
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        val target = TranslateBridge.findTranslator(packageManager, packageName)
        if (target == null) {
            Toast.makeText(this, "Tarjima uçun Google Tarjimon ilovasini örnating", Toast.LENGTH_LONG).show()
            TranslateBridge.cancel()
            TranslateBridge.openStore(this)
            finish()
            return
        }
        val i = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(target.activityInfo.packageName, target.activityInfo.name)
        try {
            startActivityForResult(i, REQ)
        } catch (e: Exception) {
            Toast.makeText(this, "Tarjima ilovasi oçilmadi", Toast.LENGTH_SHORT).show()
            TranslateBridge.cancel()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            val result = data?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (resultCode == RESULT_OK && !result.isNullOrBlank()) {
                TranslateBridge.deliver(result)
            } else {
                TranslateBridge.cancel()
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "uz.alifbo.klaviatura.TEXT"
        private const val REQ = 41
    }
}
