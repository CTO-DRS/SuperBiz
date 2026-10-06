package com.superbiz.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ═══════════ لوحة ألوان Glass — مطابقة للتصميم المرجعي ═══════════
val Vio = Color(0xFF8B5CF6)
val VioDeep = Color(0xFF7C3AED)
val Cyan = Color(0xFF22D3EE)
val Green = Color(0xFF34D399)
val GreenDeep = Color(0xFF10B981)
val Red = Color(0xFFF87171)
val RedDeep = Color(0xFFEF4444)
val Amber = Color(0xFFFBBF24)
val Pink = Color(0xFFEC4899)
val Blue = Color(0xFF3B82F6)

@Immutable
data class GlassColors(
    val isLight: Boolean,
    val bgBase: Color,
    val bgGradient: List<Color>,
    val surface: Color,
    val surfaceStrong: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accent2: Color,
    val heroBrush: Brush,
    val green: Color = Green,
    val red: Color = Red,
    val amber: Color = Amber,
    val vio: Color = Vio,
    val cyan: Color = Cyan,
    val pink: Color = Pink,
    val blue: Color = Blue,
    val chipVio: Color = Color(0x2E8B5CF6),
    val chipGreen: Color = Color(0x2E10B981),
    val chipRed: Color = Color(0x2EEF4444),
    val chipAmber: Color = Color(0x2EFBBF24),
    val chipCyan: Color = Color(0x2E22D3EE)
) {
    val dockBrush = Brush.horizontalGradient(listOf(VioDeep, Vio))
    val heroGlow = Brush.radialGradient(
        listOf(Color(0x55FFFFFF), Color.Transparent)
    )
}

private val DarkGlass = GlassColors(
    isLight = false,
    bgBase = Color(0xFF0B1026),
    bgGradient = listOf(Color(0xFF0B1026), Color(0xFF070B1A)),
    surface = Color(0x8C141B33),        // زجاج داكن
    surfaceStrong = Color(0xA6161F3D),
    border = Color(0x24FFFFFF),
    textPrimary = Color(0xFFF3F5FB),
    textSecondary = Color(0xFF9AA3C0),
    accent = Vio,
    accent2 = Cyan,
    heroBrush = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9), Color(0xFF22D3EE)))
)

private val LightGlass = GlassColors(
    isLight = true,
    bgBase = Color(0xFFF2F4FB),
    bgGradient = listOf(Color(0xFFF6F4FF), Color(0xFFEAF4FD)),
    surface = Color(0xCCFFFFFF),
    surfaceStrong = Color(0xFFFFFFFF),
    border = Color(0x1A1E2A4A),
    textPrimary = Color(0xFF101733),
    textSecondary = Color(0xFF64708F),
    accent = Color(0xFF7C3AED),
    accent2 = Color(0xFF0891B2),
    heroBrush = Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF7C3AED), Color(0xFF0EA5E9))),
    // عنبري داكن للنصوص على الأسطح الفاتحة — كان 0xFFFBBF24 بتباين ≈1.9:1
    // (غير مقروء لضعاف البصر)؛ 0xFFB45309 يحقق ≈4.6:1 وفق WCAG AA
    amber = Color(0xFFB45309)
)

val LocalGlass = staticCompositionLocalOf { DarkGlass }

@Composable
fun glassColors(): GlassColors = LocalGlass.current

@Composable
fun SuperBizTheme(
    themeMode: String,
    // ألوان النظام الديناميكية (Material You) — إعداد حقيقي من مركز الإعدادات
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkGlass else LightGlass
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val useDyn = dynamicColors && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val m3 = if (useDyn) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx)
        else androidx.compose.material3.dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme(
        primary = Vio, secondary = Cyan, tertiary = Green,
        background = Color(0xFF0B1026), surface = Color(0xFF121A36),
        surfaceVariant = Color(0xFF1A2342), onBackground = Color(0xFFF3F5FB),
        onSurface = Color(0xFFF3F5FB), onSurfaceVariant = Color(0xFF9AA3C0),
        error = RedDeep, outline = Color(0x33FFFFFF)
    ) else lightColorScheme(
        primary = Color(0xFF7C3AED), secondary = Color(0xFF0891B2), tertiary = GreenDeep,
        background = Color(0xFFF2F4FB), surface = Color.White,
        surfaceVariant = Color(0xFFEEF0FA), onBackground = Color(0xFF101733),
        onSurface = Color(0xFF101733), onSurfaceVariant = Color(0xFF64708F),
        error = RedDeep, outline = Color(0x33101733)
    )
    CompositionLocalProvider(LocalGlass provides colors) {
        MaterialTheme(
            colorScheme = m3,
            typography = BizTypography,
            content = content
        )
    }
}

val BizTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp)
)
