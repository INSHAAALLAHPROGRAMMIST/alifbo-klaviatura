package uz.alifbo.klaviatura

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Saqlangan bitta kirish: ilova, foydalanuvchi nomi (login / email / telefon) va parol. */
data class Cred(
    val id: String,
    val app: String,
    val label: String,
    val user: String,
    val pass: String,
    val updated: Long,
)

/** Kirishlar ro'yxati (faqat xotirada ochiq holda turadi; diskka faqat shifrlangan holda yoziladi). */
class VaultStore {
    private val items = ArrayList<Cred>()

    val size: Int get() = items.size

    fun all(): List<Cred> = items.sortedByDescending { it.updated }

    fun forApp(app: String): List<Cred> = all().filter { it.app == app }

    /** Bir ilovada bir foydalanuvchi nomi — bitta yozuv: yangisi eskisining parolini yangilaydi. */
    fun upsert(c: Cred): Boolean {
        val i = items.indexOfFirst { it.app == c.app && it.user.equals(c.user, ignoreCase = true) }
        if (i < 0) {
            items.add(c)
            return true
        }
        val old = items[i]
        if (old.pass == c.pass && old.user == c.user) return false
        if (old.updated > c.updated) return false
        items[i] = old.copy(user = c.user, pass = c.pass, label = c.label.ifEmpty { old.label }, updated = c.updated)
        return true
    }

    fun remove(id: String): Boolean = items.removeAll { it.id == id }

    fun clear() = items.clear()

    fun serialize(): String = items.joinToString("") { line(it) + "\n" }

    companion object {
        private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        private fun unb64(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)

        fun line(c: Cred): String = listOf(c.id, b64(c.app), b64(c.label), b64(c.user), b64(c.pass), c.updated.toString())
            .joinToString("\t")

        fun parseLine(l: String): Cred? = try {
            val p = l.split('\t')
            if (p.size < 6) null else Cred(p[0], unb64(p[1]), unb64(p[2]), unb64(p[3]), unb64(p[4]), p[5].toLong())
        } catch (e: Exception) {
            null
        }

        fun parse(text: String): VaultStore {
            val s = VaultStore()
            for (l in text.lineSequence()) {
                if (l.isBlank()) continue
                parseLine(l)?.let { s.upsert(it) }
            }
            return s
        }
    }
}

/** Maydon turi: kirish shaklidagi qaysi maydon ekani (taxminan, EditorInfo bo'yicha). */
enum class FieldKind {
    OTHER, SHORT_TEXT, USER, PASSWORD, NEW_PASSWORD;

    val isPassword: Boolean get() = this == PASSWORD || this == NEW_PASSWORD
    val isLogin: Boolean get() = this == USER || isPassword

    companion object {
        private val NEW_HINTS = listOf(
            "new", "yangi", "нов", "confirm", "qayta", "takror", "повтор", "repeat", "re-enter", "retype",
            "create", "yarat", "придума",
        )
        private val USER_HINTS = listOf(
            "login", "user", "mail", "phone", "telefon", "raqam", "логин", "почт", "телефон", "номер",
            "имя польз", "foydalanuvchi", "pochta", "account", "hisob",
        )

        fun of(info: EditorInfo): FieldKind {
            val type = info.inputType
            val cls = type and InputType.TYPE_MASK_CLASS
            val v = type and InputType.TYPE_MASK_VARIATION
            val hint = (info.hintText?.toString() ?: "").lowercase()
            val pass = (cls == InputType.TYPE_CLASS_TEXT && (
                v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                )) || (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            if (pass) return if (NEW_HINTS.any { it in hint }) NEW_PASSWORD else PASSWORD
            if (cls == InputType.TYPE_CLASS_PHONE) return USER
            if (cls != InputType.TYPE_CLASS_TEXT) return OTHER
            if (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return USER
            val multi = type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (multi) return OTHER
            if (USER_HINTS.any { it in hint }) return USER
            return if (v == InputType.TYPE_TEXT_VARIATION_NORMAL) SHORT_TEXT else OTHER
        }
    }
}

/**
 * Parollar ombori kriptografiyasi (AndroidKeyStore — telefonning himoyalangan qismi):
 * - RSA-2048 juftligi: maxfiy kalit faqat telefon qulfi (PIN / grafik kalit / barmoq izi) tasdiqlangandan
 *   keyin [VALIDITY_S] soniya ishlaydi; ochiq kalit bilan esa qulfsiz ham yangi parol shifrlab qo'yiladi.
 * - HMAC kaliti (qulfsiz): "bu ilovada saqlangan parol bormi" degan ko'rsatkich uchun, ilova nomini ochmasdan.
 */
object VaultCrypto {
    private const val KS = "AndroidKeyStore"
    private const val RSA_ALIAS = "soz_vault_rsa"
    private const val MAC_ALIAS = "soz_vault_index"
    const val VALIDITY_S = 300

    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    private val rnd = SecureRandom()

    class NoScreenLock : Exception("Ekran qulfi yo'q")

    private fun ks(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }

    fun isDeviceSecure(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    } catch (t: Throwable) {
        false
    }

    fun hasKeys(): Boolean = try {
        ks().containsAlias(RSA_ALIAS)
    } catch (t: Throwable) {
        false
    }

    /** RSA juftligini yaratadi (bir marta). Telefon qulfi bo'lmasa — [NoScreenLock]. */
    fun ensureKeys(ctx: Context) {
        if (hasKeys()) return
        if (!isDeviceSecure(ctx)) throw NoScreenLock()
        val b = KeyGenParameterSpec.Builder(RSA_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(
                VALIDITY_S,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(VALIDITY_S)
        }
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KS)
        g.initialize(b.build())
        g.generateKeyPair()
    }

    /** Ochiq kalit dasturiy ko'rinishda: shifrlash qulfsiz ishlaydi. */
    private fun publicKey(): PublicKey {
        val pub = ks().getCertificate(RSA_ALIAS).publicKey
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pub.encoded))
    }

    fun rsaEncrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        c.init(Cipher.ENCRYPT_MODE, publicKey(), oaep)
        return c.doFinal(data)
    }

    /** Telefon qulfi yaqinda tasdiqlanmagan bo'lsa — [UserNotAuthenticatedException]. */
    fun rsaDecrypt(data: ByteArray): ByteArray {
        val key = ks().getKey(RSA_ALIAS, null) as PrivateKey
        val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        c.init(Cipher.DECRYPT_MODE, key, oaep)
        return c.doFinal(data)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun aesEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun aesDecrypt(key: ByteArray, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Qulfsiz qo'shish uchun: tasodifiy AES kaliti RSA bilan o'raladi, ma'lumot AES-GCM bilan. */
    fun hybridEncrypt(plain: ByteArray): ByteArray {
        val k = randomBytes(32)
        val wrapped = rsaEncrypt(k)
        val body = aesEncrypt(k, plain)
        k.fill(0)
        return byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()) + wrapped + body
    }

    fun hybridDecrypt(blob: ByteArray): ByteArray {
        val n = ((blob[0].toInt() and 0xFF) shl 8) or (blob[1].toInt() and 0xFF)
        val k = rsaDecrypt(blob.copyOfRange(2, 2 + n))
        try {
            return aesDecrypt(k, blob.copyOfRange(2 + n, blob.size))
        } finally {
            k.fill(0)
        }
    }

    private fun macKey(): SecretKey {
        val ks = ks()
        (ks.getKey(MAC_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KS)
        g.init(KeyGenParameterSpec.Builder(MAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return g.generateKey()
    }

    fun mac(text: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(macKey())
        return Base64.getEncoder().encodeToString(m.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun deleteKeys() {
        try {
            val ks = ks()
            ks.deleteEntry(RSA_ALIAS)
            ks.deleteEntry(MAC_ALIAS)
        } catch (t: Throwable) {
            // yo'q bo'lsa ham bo'ladi
        }
    }

    // ---------------------------------------------------------------- zaxira nusxa (bosh parol bilan)

    private const val MAGIC = "SOZPAROL1"
    private const val ITERATIONS = 210_000

    private fun derive(pass: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(pass, salt, iterations, 256)).encoded
    }

    fun exportBlob(plain: String, pass: CharArray): ByteArray {
        val salt = randomBytes(16)
        val key = derive(pass, salt, ITERATIONS)
        val body = aesEncrypt(key, plain.toByteArray(Charsets.UTF_8))
        key.fill(0)
        val e = Base64.getEncoder()
        return "$MAGIC\n${e.encodeToString(salt)}\n$ITERATIONS\n${e.encodeToString(body)}\n".toByteArray(Charsets.UTF_8)
    }

    /** Bosh parol noto'g'ri yoki fayl buzuq bo'lsa — null. */
    fun importBlob(data: ByteArray, pass: CharArray): String? = try {
        val lines = String(data, Charsets.UTF_8).lines()
        if (lines.size < 4 || lines[0] != MAGIC) {
            null
        } else {
            val d = Base64.getDecoder()
            val key = derive(pass, d.decode(lines[1]), lines[2].toInt())
            try {
                String(aesDecrypt(key, d.decode(lines[3])), Charsets.UTF_8)
            } finally {
                key.fill(0)
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Parollar ombori. Diskda: vault.dat (RSA bilan o'ralgan ma'lumot kaliti + AES-GCM bilan shifrlangan ro'yxat),
 * vault_inbox.txt (qulf ochilmagan paytda qo'shilganlar, har biri alohida shifrlangan),
 * vault_index.txt (faqat HMAC ko'rsatkichlar: ilova nomi ham, parol ham ochiq yozilmaydi).
 * Ochiq ro'yxat faqat xotirada turadi; ekran o'chganda yoki [IDLE_MS] dan keyin yana qulflanadi.
 */
object Vault {
    private const val FILE = "vault.dat"
    private const val INBOX = "vault_inbox.txt"
    private const val INDEX = "vault_index.txt"
    private const val IDLE_MS = 15 * 60 * 1000L

    enum class Open { OPEN, NEED_AUTH, NO_LOCK, BROKEN }

    private var store: VaultStore? = null
    private var dataKey: ByteArray? = null
    private var wrappedKey: String? = null
    private var lastUse = 0L

    val isOpen: Boolean
        @Synchronized get() {
            if (store != null && System.currentTimeMillis() - lastUse > IDLE_MS) lock()
            return store != null
        }

    fun newId(): String = VaultCrypto.randomBytes(9).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
        wrappedKey = null
        store = null
    }

    /** Ochishga urinadi: telefon qulfi yaqinda tasdiqlangan bo'lsa so'roqsiz ochiladi. */
    @Synchronized
    fun tryOpen(ctx: Context): Open {
        if (isOpen) {
            lastUse = System.currentTimeMillis()
            return Open.OPEN
        }
        return try {
            open(ctx)
            Open.OPEN
        } catch (e: UserNotAuthenticatedException) {
            Open.NEED_AUTH
        } catch (e: KeyPermanentlyInvalidatedException) {
            Open.BROKEN
        } catch (e: VaultCrypto.NoScreenLock) {
            Open.NO_LOCK
        } catch (e: Exception) {
            if (e.cause is UserNotAuthenticatedException) Open.NEED_AUTH else Open.BROKEN
        }
    }

    private fun open(ctx: Context) {
        VaultCrypto.ensureKeys(ctx)
        val f = File(ctx.filesDir, FILE)
        val d = Base64.getDecoder()
        val s: VaultStore
        val key: ByteArray
        val wrapped: String
        var changed = false
        if (f.exists()) {
            val lines = f.readLines()
            wrapped = lines[0]
            key = VaultCrypto.rsaDecrypt(d.decode(wrapped))
            s = if (lines.size > 1 && lines[1].isNotBlank()) {
                VaultStore.parse(String(VaultCrypto.aesDecrypt(key, d.decode(lines[1])), Charsets.UTF_8))
            } else {
                VaultStore()
            }
        } else {
            key = VaultCrypto.randomBytes(32)
            wrapped = Base64.getEncoder().encodeToString(VaultCrypto.rsaEncrypt(key))
            s = VaultStore()
            changed = true
        }
        val inbox = File(ctx.filesDir, INBOX)
        if (inbox.exists()) {
            for (l in inbox.readLines()) {
                if (l.isBlank()) continue
                val c = VaultStore.parseLine(String(VaultCrypto.hybridDecrypt(d.decode(l)), Charsets.UTF_8))
                if (c != null && s.upsert(c)) changed = true
            }
        }
        store = s
        dataKey = key
        wrappedKey = wrapped
        lastUse = System.currentTimeMillis()
        if (changed || inbox.exists()) {
            save(ctx)
            inbox.delete()
        }
    }

    private fun save(ctx: Context) {
        val s = store ?: return
        val k = dataKey ?: return
        val body = Base64.getEncoder().encodeToString(VaultCrypto.aesEncrypt(k, s.serialize().toByteArray(Charsets.UTF_8)))
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText("$wrappedKey\n$body\n")
        tmp.renameTo(File(ctx.filesDir, FILE))
        rebuildIndex(ctx, s)
    }

    private fun appKey(app: String) = "a:" + VaultCrypto.mac("app\u0000$app")

    private fun credKey(app: String, user: String, pass: String) =
        "c:" + VaultCrypto.mac("cred\u0000$app\u0000${user.lowercase()}\u0000$pass")

    private fun rebuildIndex(ctx: Context, s: VaultStore) {
        val lines = s.all().flatMap { listOf(appKey(it.app), credKey(it.app, it.user, it.pass)) }
        File(ctx.filesDir, INDEX).writeText(lines.joinToString("") { "$it\n" })
    }

    private fun indexLines(ctx: Context): List<String> = try {
        val f = File(ctx.filesDir, INDEX)
        if (f.exists()) f.readLines() else emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** Qulfsiz: bu ilova uchun saqlangan parol bormi. */
    fun hasEntriesFor(ctx: Context, app: String): Boolean = try {
        if (isOpen) store!!.forApp(app).isNotEmpty() else appKey(app) in indexLines(ctx)
    } catch (t: Throwable) {
        false
    }

    /** Qulfsiz: aynan shu kirish saqlanganmi (takror eslab qolmaslik uchun). */
    fun isSaved(ctx: Context, app: String, user: String, pass: String): Boolean = try {
        credKey(app, user, pass) in indexLines(ctx)
    } catch (t: Throwable) {
        false
    }

    /** Saqlangan kirishlar soni (qulfsiz, ko'rsatkichdan). */
    fun count(ctx: Context): Int = indexLines(ctx).count { it.startsWith("a:") }

    fun all(): List<Cred> = if (isOpen) store!!.all() else emptyList()

    fun forApp(app: String): List<Cred> = if (isOpen) store!!.forApp(app) else emptyList()

    /** Yangi kirishni eslab qoladi: ochiq bo'lsa — to'g'ridan-to'g'ri, qulfli bo'lsa — ochiq kalit bilan shifrlab. */
    @Synchronized
    fun remember(ctx: Context, c: Cred) {
        if (isOpen) {
            if (store!!.upsert(c)) save(ctx)
            lastUse = System.currentTimeMillis()
            return
        }
        VaultCrypto.ensureKeys(ctx)
        val line = Base64.getEncoder().encodeToString(
            VaultCrypto.hybridEncrypt(VaultStore.line(c).toByteArray(Charsets.UTF_8)),
        )
        File(ctx.filesDir, INBOX).appendText("$line\n")
        File(ctx.filesDir, INDEX).appendText("${appKey(c.app)}\n${credKey(c.app, c.user, c.pass)}\n")
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val s = store ?: return
        if (s.remove(id)) save(ctx)
    }

    /** Ochiq ro'yxatni bosh parol bilan shifrlangan zaxira faylga aylantiradi. */
    fun exportBlob(pass: CharArray): ByteArray? {
        val s = store ?: return null
        return VaultCrypto.exportBlob(s.serialize(), pass)
    }

    /** Zaxira fayldan tiklaydi; tiklangan yozuvlar soni yoki bosh parol noto'g'ri bo'lsa null. */
    fun importBlob(ctx: Context, data: ByteArray, pass: CharArray): Int? {
        val text = VaultCrypto.importBlob(data, pass) ?: return null
        val items = VaultStore.parse(text).all()
        for (c in items) remember(ctx, c)
        return items.size
    }

    /** Hammasini o'chiradi: fayllar va kalitlar. */
    @Synchronized
    fun wipe(ctx: Context) {
        lock()
        for (n in listOf(FILE, INBOX, INDEX, "$FILE.tmp")) File(ctx.filesDir, n).delete()
        VaultCrypto.deleteKeys()
    }
}

/** Qulfni ochish oynasi ([UnlockActivity]) bilan aloqa. */
object VaultBridge {
    private var callback: ((Boolean) -> Unit)? = null

    fun requestUnlock(ctx: Context, cb: (Boolean) -> Unit) {
        callback = cb
        try {
            ctx.startActivity(Intent(ctx, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            finish(false)
        }
    }

    fun finish(ok: Boolean) {
        val c = callback
        callback = null
        c?.invoke(ok)
    }
}
