package com.akshay.offlineasr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme = darkColorScheme(
  primary = Mint,
  onPrimary = NearBlack,
  secondary = Mint,
  onSecondary = NearBlack,
  background = NearBlack,
  onBackground = TextPrimary,
  surface = NearBlack,
  onSurface = TextPrimary,
  surfaceVariant = Panel,
  onSurfaceVariant = TextMuted,
  surfaceContainer = Panel,
  surfaceContainerHigh = PanelHigh,
  outline = Outline,
  error = Miss,
)

/** Always dark, high contrast — no dynamic color so the video looks identical on every phone. */
@Composable
fun OfflineHinglishASRTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
