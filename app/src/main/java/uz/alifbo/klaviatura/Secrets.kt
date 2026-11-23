package uz.alifbo.klaviatura

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kichik sirlar (tarjima xizmati API kaliti): telefonning himoyalangan kalitlar omboridagi (AndroidKeyStore)
 * AES-256 kaliti bilan shifrlangan holda ilovaning shaxsiy papkasida saqlanadi. Kalitni olish uchun
 * qulf so'ralmaydi (tarjima qulfsiz ishlashi kerak), lekin fayl ilovadan tashqarida o'qilmaydi.
 */
object Secrets {
    private const val ALIAS = "soz_secrets"
    private const val FILE = "secrets.txt"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun readAll(ctx: Context): MutableMap<String, String> {
        val f = File(ctx.filesDir, FILE)
        val m = LinkedHashMap<String, String>()
        if (!f.exists()) return m
        for (line in f.readLines()) {
            val i = line.indexOf('\t')
            if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
        }
        return m
    }

    /** Foydalanuvchi saqlagan kalit; bo'lmasa — ilovaga CI'da qotirilgan standart kalit (bo'lsa). */
    fun get(ctx: Context, name: String): String? = stored(ctx, name) ?: defaultFor(name)

    /** Foydalanuvchi o'zi saqlagan (sozlamadan kiritgan) kalit bormi — standart kalitni hisobga olmaydi. */
    fun has(ctx: Context, name: String): Boolean = try {
        readAll(ctx).containsKey(name)
    } catch (t: Throwable) {
        false
    }

    /** Hozir ishlatilayotgan kalit standart (CI'da qotirilgan), foydalanuvchi o'zi kiritmaganmi. */
    fun usingDefault(ctx: Context, name: String): Boolean = !has(ctx, name) && defaultFor(name) != null

    private fun stored(ctx: Context, name: String): String? = try {
        readAll(ctx)[name]?.let { enc ->
            val raw = Base64.getDecoder().decode(enc)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }

    private fun defaultFor(name: String): String? =
        if (name == Translator.KEY_NAME) BuildConfig.DEFAULT_GOOGLE_KEY.takeIf { it.isNotBlank() } else null

    /** [value] = null — o'chiradi. */
    fun put(ctx: Context, name: String, value: String?): Boolean = try {
        val m = readAll(ctx)
        if (value == null) {
            m.remove(name)
        } else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            m[name] = Base64.getEncoder().encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)))
        }
        File(ctx.filesDir, FILE).writeText(m.entries.joinToString("") { "${it.key}\t${it.value}\n" })
        true
    } catch (t: Throwable) {
        false
    }
}
