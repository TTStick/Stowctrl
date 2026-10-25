package com.wanwu.nook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.wanwu.nook.data.RoomType
import com.wanwu.nook.data.info

/** App-specific colours beyond the Material scheme (same values as iOS). */
data class NookColors(
    val dark: Boolean,
    val canvas: Color,
    val card: Color,
    val photo: Color,
    val wall: Color,
) {
    fun floor(type: RoomType) = Color(if (dark) type.info.floorDark else type.info.floorLight)
}

val LocalNookColors = staticCompositionLocalOf { lightNook }

private val lightNook = NookColors(false, Color(0xFFF4F2EE), Color.White, Color(0xFFF1EEE8), Color(0xFF4A4A4A))
private val darkNook = NookColors(true, Color(0xFF161616), Color(0xFF232323), Color(0xFF2A2A2A), Color(0xFF9A9A9A))

val Teal = Color(0xFF2A7F76)
val TealDark = Color(0xFF4FB3A9)

@Composable
fun NookTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = TealDark, onPrimary = Color(0xFF00201C), primaryContainer = Color(0xFF1F4F4A),
        onPrimaryContainer = Color(0xFFB8F0E7), background = darkNook.canvas, surface = darkNook.canvas,
        surfaceContainer = Color(0xFF1E1E1E), surfaceContainerLow = darkNook.card, surfaceContainerHigh = Color(0xFF2C2C2C),
    ) else lightColorScheme(
        primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFCDEBE6),
        onPrimaryContainer = Color(0xFF00201C), background = lightNook.canvas, surface = lightNook.canvas,
        surfaceContainer = Color(0xFFF0EEEA), surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFEAE7E2),
    )
    CompositionLocalProvider(LocalNookColors provides if (dark) darkNook else lightNook) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

fun hexColor(hex: String?): Color {
    val s = (hex ?: "#8E8E93").removePrefix("#")
    return runCatching {
        val v = s.toLong(16)
        if (s.length == 3) {
            Color(((v shr 8) and 0xF) * 17 / 255f, ((v shr 4) and 0xF) * 17 / 255f, (v and 0xF) * 17 / 255f)
        } else Color(0xFF000000 or v)
    }.getOrDefault(Color.Gray)
}

fun Color.toHex(): String = "#%02x%02x%02x".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
