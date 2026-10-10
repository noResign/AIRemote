package com.noresign.paboot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = DesignColors.LightPrimary,
    onPrimary = Color.White,
    primaryContainer = DesignColors.LightPrimarySubtle,
    onPrimaryContainer = DesignColors.LightPrimaryDeep,
    secondary = DesignColors.LightPrimaryDeep,
    onSecondary = Color.White,
    secondaryContainer = DesignColors.LightPrimarySubtle,
    onSecondaryContainer = DesignColors.LightPrimaryDeep,
    tertiary = DesignColors.ClaudeOrange,
    onTertiary = Color.White,
    background = DesignColors.LightBg,
    onBackground = DesignColors.LightTextPrimary,
    surface = DesignColors.LightSurface,
    onSurface = DesignColors.LightTextPrimary,
    surfaceVariant = DesignColors.LightCodeBg,
    onSurfaceVariant = DesignColors.LightTextSecondary,
    outline = DesignColors.LightBorder,
    outlineVariant = DesignColors.LightBorder,
    error = DesignColors.LightError,
    onError = Color.White,
    errorContainer = DesignColors.LightErrorContainer,
    onErrorContainer = DesignColors.LightError,
)

private val DarkColorScheme = darkColorScheme(
    primary = DesignColors.DarkPrimary,
    onPrimary = Color(0xFF06231A),
    primaryContainer = DesignColors.DarkPrimarySubtle,
    onPrimaryContainer = DesignColors.DarkPrimaryDeep,
    secondary = DesignColors.DarkPrimaryDeep,
    onSecondary = Color(0xFF06231A),
    secondaryContainer = DesignColors.DarkPrimarySubtle,
    onSecondaryContainer = DesignColors.DarkPrimaryDeep,
    tertiary = DesignColors.ClaudeOrange,
    onTertiary = Color(0xFF2B1500),
    background = DesignColors.DarkBg,
    onBackground = DesignColors.DarkTextPrimary,
    surface = DesignColors.DarkSurface,
    onSurface = DesignColors.DarkTextPrimary,
    surfaceVariant = DesignColors.DarkCodeBg,
    onSurfaceVariant = DesignColors.DarkTextSecondary,
    outline = DesignColors.DarkBorder,
    outlineVariant = DesignColors.DarkBorder,
    error = DesignColors.DarkError,
    onError = Color(0xFF2B0D0C),
    errorContainer = DesignColors.DarkErrorContainer,
    onErrorContainer = DesignColors.DarkError,
)

/** Material3 ColorScheme 没有的语义色（success/warning/thinking），随主题切换。 */
data class SemanticColors(
    val success: Color,
    val warning: Color,
    val thinking: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        success = DesignColors.LightSuccess,
        warning = DesignColors.LightWarning,
        thinking = DesignColors.LightThinking,
    )
}

/**
 * 应用主题：默认浅色、跟随系统自动切（isSystemInDarkTheme）。
 *
 * 刻意不使用 Material 的 dynamicColor —— 它会用系统壁纸色覆盖青绿主色，破坏设计 token。
 */
@Composable
fun PabootTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val semanticColors = if (darkTheme) {
        SemanticColors(
            success = DesignColors.DarkSuccess,
            warning = DesignColors.DarkWarning,
            thinking = DesignColors.DarkThinking,
        )
    } else {
        SemanticColors(
            success = DesignColors.LightSuccess,
            warning = DesignColors.LightWarning,
            thinking = DesignColors.LightThinking,
        )
    }

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
