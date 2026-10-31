package io.github.bigtimer_dev.ircam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ui {
    val Bg = Color(0xFF0B0B0F)
    val Panel = Color(0xFF16161C)
    val PanelHigh = Color(0xFF202029)
    val Line = Color(0xFF2C2C36)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFF9A9AA6)
    val Accent = Color(0xFFFFB020)
    val Hot = Color(0xFFFF5A4E)
    val Cold = Color(0xFF4DA8FF)
    val Rec = Color(0xFFFF3B30)
}

private val scheme = darkColorScheme(
    primary = Ui.Accent,
    onPrimary = Color(0xFF231600),
    primaryContainer = Color(0xFF3D2A00),
    onPrimaryContainer = Color(0xFFFFDDA6),
    secondary = Color(0xFFD9C4A0),
    secondaryContainer = Ui.PanelHigh,
    onSecondaryContainer = Ui.Text,
    background = Ui.Bg,
    onBackground = Ui.Text,
    surface = Ui.Bg,
    onSurface = Ui.Text,
    surfaceVariant = Ui.Panel,
    onSurfaceVariant = Ui.TextDim,
    surfaceContainerLowest = Ui.Bg,
    surfaceContainerLow = Ui.Panel,
    surfaceContainer = Ui.Panel,
    surfaceContainerHigh = Ui.PanelHigh,
    surfaceContainerHighest = Color(0xFF2A2A34),
    outline = Color(0xFF4A4A56),
    outlineVariant = Ui.Line,
    error = Color(0xFFFF6B60),
)

@Composable
fun IrcamTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

/** Big tabular numbers for temperature readouts. */
val ReadoutStyle = TextStyle(
    fontSize = 22.sp,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)
