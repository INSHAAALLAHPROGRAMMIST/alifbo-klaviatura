package uz.alifbo.klaviatura

import java.util.Base64

/** Nusxa olingan bitta matn. [time] — nusxa olingan vaqt (ms), [pinned] — qadab qo'yilgan. */
data class Clip(val text: String, val time: Long, val pinned: Boolean = false)

/**
 * Nusxalar tarixi ("bufer"): har yangi nusxa tepaga qo'shiladi, eskilari saqlanib turadi.
 * Qadalganlari hech qachon avtomatik o'chirilmaydi; qadalmaganlardan eng ko'pi [maxUnpinned] ta qoladi.
 * Faqat telefonda, ilovaning shaxsiy papkasida saqlanadi; tarmoqqa chiqmaydi.
 */
class ClipboardStore(
    private val maxUnpinned: Int = 100,
    private val maxLength: Int = 5000,
) {
    private val items = ArrayList<Clip>()

    var dirty: Boolean = false
        private set

    val size: Int get() = items.size

    /** Ko'rsatish tartibi: qadalganlari tepada, keyin eng yangisidan eskisiga. */
    fun sorted(): List<Clip> =
        items.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })

    /** Eng oxirgi nusxa (qadalganmi-yo'qmi farqi yo'q). */
    fun latest(): Clip? = items.maxByOrNull { it.time }

    /** Yangi nusxa; bir xil matn bo'lsa tepaga ko'tariladi (qadalganligi saqlanadi). */
    fun add(text: String, now: Long): Boolean {
        val t = if (text.length > maxLength) text.substring(0, maxLength) else text
        if (t.isBlank()) return false
        val i = items.indexOfFirst { it.text == t }
        if (i >= 0 && items[i].time == now) return false
        val pinned = i >= 0 && items[i].pinned
        if (i >= 0) items.removeAt(i)
        items.add(0, Clip(t, now, pinned))
        trim()
        dirty = true
        return true
    }

    fun togglePin(text: String) {
        val i = items.indexOfFirst { it.text == text }
        if (i < 0) return
        items[i] = items[i].copy(pinned = !items[i].pinned)
        trim()
        dirty = true
    }

    fun remove(text: String) {
        if (items.removeAll { it.text == text }) dirty = true
    }

    /** Qadalmaganlarni o'chiradi. */
    fun clearUnpinned() {
        if (items.removeAll { !it.pinned }) dirty = true
    }

    fun clearAll() {
        if (items.isNotEmpty()) {
            items.clear()
            dirty = true
        }
    }

    fun markSaved() {
        dirty = false
    }

    private fun trim() {
        var unpinned = 0
        val newestFirst = items.sortedByDescending { it.time }
        val drop = HashSet<Clip>()
        for (c in newestFirst) {
            if (c.pinned) continue
            unpinned++
            if (unpinned > maxUnpinned) drop.add(c)
        }
        if (drop.isNotEmpty()) items.removeAll { it in drop }
    }

    /** Har yozuv bir qatorda: vaqt, qadalganmi, matn (base64 — ichida yangi qator bo'lishi mumkin). */
    fun serialize(): String {
        val enc = Base64.getEncoder()
        return items.joinToString("\n") { c ->
            "${c.time}\t${if (c.pinned) 1 else 0}\t" + enc.encodeToString(c.text.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        fun parse(text: String, maxUnpinned: Int = 100): ClipboardStore {
            val s = ClipboardStore(maxUnpinned)
            val dec = Base64.getDecoder()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                if (f.size != 3) continue
                val time = f[0].toLongOrNull() ?: continue
                val body = try {
                    String(dec.decode(f[2]), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    continue
                }
                s.items.add(Clip(body, time, f[1] == "1"))
            }
            s.dirty = false
            return s
        }
    }
}
