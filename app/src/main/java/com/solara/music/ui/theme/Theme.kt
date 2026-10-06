package com.solara.music.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 按主题色板构建明/暗 ColorScheme（v1.4.13 #64）。
 * 主色取自所选色板，辅助色（secondary=蓝、tertiary=珊瑚橙）保持原设计。
 */
private fun lightScheme(a: AccentPalette) = lightColorScheme(
    primary = a.c30,
    onPrimary = Color.White,
    primaryContainer = a.c90,
    onPrimaryContainer = a.c10,
    secondary = Blue30,
    onSecondary = Blue95,
    secondaryContainer = Blue90,
    onSecondaryContainer = Color(0xFF042C53),
    tertiary = Coral30,
    onTertiary = Coral95,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Color(0xFF4A1B0C),
    background = NeutralBgLight,
    onBackground = Color(0xFF17201C),
    surface = Color.White,
    onSurface = Color(0xFF17201C),
    surfaceVariant = a.c95,
    onSurfaceVariant = Color(0xFF404945),
    outline = OutlineLight,
    outlineVariant = Color(0xFFD3D1C7)
)

private fun darkScheme(a: AccentPalette) = darkColorScheme(
    primary = a.c80,
    onPrimary = a.c10,
    primaryContainer = a.c30,
    onPrimaryContainer = a.c90,
    secondary = Blue80,
    onSecondary = Color(0xFF042C53),
    secondaryContainer = Color(0xFF0C447C),
    onSecondaryContainer = Blue90,
    tertiary = Coral80,
    onTertiary = Color(0xFF4A1B0C),
    tertiaryContainer = Color(0xFF712B13),
    onTertiaryContainer = Coral90,
    background = NeutralBgDark,
    onBackground = Color(0xFFE0E6E2),
    surface = NeutralSurfaceDark,
    onSurface = Color(0xFFE0E6E2),
    surfaceVariant = NeutralVariantDark,
    onSurfaceVariant = Color(0xFFB8C2BD),
    outline = OutlineDark,
    outlineVariant = Color(0xFF2C3733)
)

/** 兼容旧引用（薄荷绿默认）。 */
private val LightColors = lightScheme(AccentPalettes.mint)
private val DarkColors = darkScheme(AccentPalettes.mint)

/**
 * 界面缩放级别 → Density 系数（v1.5.1 r67）。
 * 0=更小 1=小 2=标准 3=大 4=更大。
 */
val UiScaleFactors = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f)

@Composable
fun SolaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accentColor: String = "mint",
    uiScaleLevel: Int = 2,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkScheme(AccentPalettes.of(accentColor))
        else -> lightScheme(AccentPalettes.of(accentColor))
    }
    // v1.5.1 r67：界面整体缩放——覆盖 LocalDensity 的 density 部分
    // （dp 与 sp 同比例缩放：字体、图标、封面统一变大/变小）；
    // fontScale 保持系统值（标准级 = 系统默认行为，选小可抵消系统大字体）。
    // StateFlow 驱动 → 切换即时生效，无需重启 Activity。
    val base = LocalDensity.current
    val factor = UiScaleFactors[uiScaleLevel.coerceIn(0, 4)]
    val scaled = if (factor == 1.0f) base
    else Density(base.density * factor, base.fontScale)
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SolaraTypography,
            content = content
        )
    }
}
