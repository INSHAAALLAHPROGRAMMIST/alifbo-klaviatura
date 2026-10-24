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

    /** AMOLED ekranlar uchun to'liq qora. */
    val night = KbTheme(
        background = 0xFF000000.toInt(),
        key = 0xFF1C1C1E.toInt(),
        keySpecial = 0xFF121214.toInt(),
        keyPressed = 0xFF3A3A3C.toInt(),
        text = 0xFFF2F2F7.toInt(),
        textSpecial = 0xFFC7C7CC.toInt(),
        accent = 0xFF0A84FF.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        popup = 0xFF2C2C2E.toInt(),
        popupText = 0xFFF2F2F7.toInt(),
        hint = 0xFF8E8E93.toInt(),
    )

    val green = KbTheme(
        background = 0xFFE4EEE7.toInt(),
        key = 0xFFFFFFFF.toInt(),
        keySpecial = 0xFFCFDDD3.toInt(),
        keyPressed = 0xFFBBCDBF.toInt(),
        text = 0xFF14291C.toInt(),
        textSpecial = 0xFF2F4A39.toInt(),
        accent = 0xFF1F8A57.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        popup = 0xFFFFFFFF.toInt(),
        popupText = 0xFF14291C.toInt(),
        hint = 0xFF6E8577.toInt(),
    )

    val sand = KbTheme(
        background = 0xFFEEE6D9.toInt(),
        key = 0xFFFFFBF3.toInt(),
        keySpecial = 0xFFDCD0BD.toInt(),
        keyPressed = 0xFFCDBFA8.toInt(),
        text = 0xFF2B2118.toInt(),
        textSpecial = 0xFF4A3B2C.toInt(),
        accent = 0xFFC0652B.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        popup = 0xFFFFFBF3.toInt(),
        popupText = 0xFF2B2118.toInt(),
        hint = 0xFF8C7B66.toInt(),
    )

    val purple = KbTheme(
        background = 0xFF17132A.toInt(),
        key = 0xFF2A2346.toInt(),
        keySpecial = 0xFF201B38.toInt(),
        keyPressed = 0xFF3E3566.toInt(),
        text = 0xFFEEEAFE.toInt(),
        textSpecial = 0xFFCFC7F2.toInt(),
        accent = 0xFF8F6BFF.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        popup = 0xFF352C58.toInt(),
        popupText = 0xFFEEEAFE.toInt(),
        hint = 0xFF8E86B0.toInt(),
    )

    val sea = KbTheme(
        background = 0xFF0E1A24.toInt(),
        key = 0xFF1B2B39.toInt(),
        keySpecial = 0xFF15222E.toInt(),
        keyPressed = 0xFF2B4053.toInt(),
        text = 0xFFE6F1F8.toInt(),
        textSpecial = 0xFFB8CCD9.toInt(),
        accent = 0xFF2BB3C0.toInt(),
        accentText = 0xFF06212A.toInt(),
        popup = 0xFF22374A.toInt(),
        popupText = 0xFFE6F1F8.toInt(),
        hint = 0xFF7D95A7.toInt(),
    )

    /** Sozlamalardagi tartib: (rejim raqami, nomi). 0 = tizimga ergashadi, 3 = maxsus ranglar. */
    val choices: List<Pair<Int, String>> = listOf(
        0 to "Tizim",
        1 to "Yoruğ",
        2 to "Qora",
        4 to "Tun",
        5 to "Yaşil",
        6 to "Qumloq",
        7 to "Binafşa",
        8 to "Dengiz",
        3 to "Maxsus",
    )

    fun preset(mode: Int, night: Boolean): KbTheme? = when (mode) {
        0 -> if (night) dark else light
        1 -> light
        2 -> dark
        4 -> Themes.night
        5 -> green
        6 -> sand
        7 -> purple
        8 -> sea
        else -> null
    }

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
