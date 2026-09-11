package com.solara.music.ui.theme

import androidx.compose.ui.graphics.Color

// 清新薄荷绿主色调（Light）——默认配色
val Teal10 = Color(0xFF04342C)
val Teal20 = Color(0xFF085041)
val Teal30 = Color(0xFF0F6E56)
val Teal80 = Color(0xFF5DCAA5)
val Teal90 = Color(0xFF9FE1CB)
val Teal95 = Color(0xFFE1F5EE)

// 天空蓝
val Sky10 = Color(0xFF001D36)
val Sky30 = Color(0xFF0061A4)
val Sky80 = Color(0xFF9ECAFF)
val Sky90 = Color(0xFFD1E4FF)
val Sky95 = Color(0xFFECF3FF)

// 梦幻紫
val Purple10 = Color(0xFF21005D)
val Purple30 = Color(0xFF6750A4)
val Purple80 = Color(0xFFD0BCFF)
val Purple90 = Color(0xFFEADDFF)
val Purple95 = Color(0xFFF6EDFF)

// 樱花粉
val Pink10 = Color(0xFF3E001F)
val Pink30 = Color(0xFFA62B5F)
val Pink80 = Color(0xFFFFB1C3)
val Pink90 = Color(0xFFFFD9E0)
val Pink95 = Color(0xFFFFF0F3)

// 活力橙
val Orange10 = Color(0xFF2B1500)
val Orange30 = Color(0xFF8F4A00)
val Orange80 = Color(0xFFFFB86F)
val Orange90 = Color(0xFFFFDDB8)
val Orange95 = Color(0xFFFFF2E0)

// 玫瑰红
val Rose10 = Color(0xFF3D0905)
val Rose30 = Color(0xFFA0372F)
val Rose80 = Color(0xFFF2B8B5)
val Rose90 = Color(0xFFF9DEDB)
val Rose95 = Color(0xFFFCEFED)

// 辅助蓝（Dark/强调）
val Blue30 = Color(0xFF185FA5)
val Blue80 = Color(0xFF85B7EB)
val Blue90 = Color(0xFFB5D4F4)
val Blue95 = Color(0xFFE6F1FB)

// 点缀珊瑚橙
val Coral30 = Color(0xFF993C1D)
val Coral80 = Color(0xFFF0997B)
val Coral90 = Color(0xFFF5C4B3)
val Coral95 = Color(0xFFFAECE7)

// 中性色
val NeutralBgLight = Color(0xFFF7FBF9)
val NeutralSurfaceDark = Color(0xFF151B19)
val NeutralBgDark = Color(0xFF101513)
val NeutralVariantDark = Color(0xFF1F2A26)
val OutlineLight = Color(0xFFB4B2A9)
val OutlineDark = Color(0xFF4A544F)

/**
 * v1.4.13 #64：外观主题色板。每套色提供 M3 色调梯度（10/30/80/90/95），
 * Theme.kt 据此构建明暗两套 ColorScheme。
 */
data class AccentPalette(
    val key: String,
    val label: String,
    val c10: Color,
    val c30: Color,
    val c80: Color,
    val c90: Color,
    val c95: Color
)

object AccentPalettes {
    val mint = AccentPalette("mint", "薄荷绿", Teal10, Teal30, Teal80, Teal90, Teal95)
    val sky = AccentPalette("sky", "天空蓝", Sky10, Sky30, Sky80, Sky90, Sky95)
    val purple = AccentPalette("purple", "梦幻紫", Purple10, Purple30, Purple80, Purple90, Purple95)
    val pink = AccentPalette("pink", "樱花粉", Pink10, Pink30, Pink80, Pink90, Pink95)
    val orange = AccentPalette("orange", "活力橙", Orange10, Orange30, Orange80, Orange90, Orange95)
    val rose = AccentPalette("rose", "玫瑰红", Rose10, Rose30, Rose80, Rose90, Rose95)

    val all = listOf(mint, sky, purple, pink, orange, rose)

    fun of(key: String): AccentPalette =
        all.firstOrNull { it.key == key } ?: mint
}
