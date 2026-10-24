package uz.alifbo.klaviatura

import androidx.core.graphics.ColorUtils

data class KbTheme(
    val background: Int,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val text: Int,
    val textSpecial: Int,
    val accent: Int,
    val accentText: Int,
    val popup: Int,
    val popupText: Int,
    val hint: Int,
) {
    val isDark: Boolean get() = ColorUtils.calculateLuminance(background) < 0.4
}

object Themes {
    val light = KbTheme(
        background = 0xFFE9EBEF.toInt(),
        key = 0xFFFFFFFF.toInt(),
        keySpecial = 0xFFD5D9E0.toInt(),
        keyPressed = 0xFFC3C9D4.toInt(),
        text = 0xFF1B1E24.toInt(),
        textSpecial = 0xFF3A404B.toInt(),
        accent = 0xFF2F6FED.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        popup = 0xFFFFFFFF.toInt(),
        popupText = 0xFF1B1E24.toInt(),
        hint = 0xFF8A919E.toInt(),
    )

    val dark = KbTheme(
        background = 0xFF14161A.toInt(),
        key = 0xFF2A2D34.toInt(),
        keySpecial = 0xFF1F2227.toInt(),
        keyPressed = 0xFF454A55.toInt(),
        text = 0xFFF1F3F6.toInt(),
        textSpecial = 0xFFC9CED8.toInt(),
        accent = 0xFF5B8CFF.toInt(),
        accentText = 0xFF0B1220.toInt(),
        popup = 0xFF3A3E47.toInt(),
        popupText = 0xFFF1F3F6.toInt(),
        hint = 0xFF7D8491.toInt(),
    )

    /** To'rtta rangdan qolganlarini hosil qiladi (maxsus tema). */
    fun custom(background: Int, key: Int, text: Int, accent: Int): KbTheme {
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        return KbTheme(
            background = background,
            key = key,
            keySpecial = ColorUtils.blendARGB(background, text, 0.10f),
            keyPressed = ColorUtils.blendARGB(key, text, 0.18f),
            text = text,
            textSpecial = ColorUtils.blendARGB(text, background, 0.2f),
            accent = accent,
            accentText = onAccent,
            popup = key,
            popupText = text,
            hint = ColorUtils.blendARGB(text, background, 0.55f),
        )
    }
}
