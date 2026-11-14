package de.ovgu.imiq.companion

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** IMIQ Project Design Styleguide, 16 March 2026, printed pp.18–23.
 * Use the specified swatches, not calculated percentage tints. The guide's 50%
 * HEX/RGB annotations disagree; those ambiguous variants are deliberately unused.
 */
object CompanionPalette {
 val Primary=Color(0xFF623CEA)
 val Blue=Color(0xFFA5C4D4)
 val Burgundy=Color(0xFF7A003F)
 val Lavender=Color(0xFFD0C9E5)
 val Background=Color(0xFFE4EBF2)
 val Rose=Color(0xFFF4E9EF)
 val Text=Color.Black
 val Surface=Color.White
 val Muted=Color.Black.copy(alpha=.68f)
}

internal val companionColorScheme=lightColorScheme(
 primary=CompanionPalette.Primary,onPrimary=Color.White,
 primaryContainer=CompanionPalette.Lavender,onPrimaryContainer=Color.Black,
 inversePrimary=CompanionPalette.Lavender,
 secondary=CompanionPalette.Burgundy,onSecondary=Color.White,
 secondaryContainer=CompanionPalette.Rose,onSecondaryContainer=CompanionPalette.Burgundy,
 tertiary=CompanionPalette.Blue,onTertiary=Color.Black,
 tertiaryContainer=CompanionPalette.Background,onTertiaryContainer=Color.Black,
 background=CompanionPalette.Background,onBackground=Color.Black,
 surface=Color.White,onSurface=Color.Black,
 surfaceVariant=CompanionPalette.Background,onSurfaceVariant=CompanionPalette.Muted,
 surfaceTint=Color.Transparent,surfaceDim=CompanionPalette.Background,surfaceBright=Color.White,
 surfaceContainerLowest=Color.White,surfaceContainerLow=Color.White,surfaceContainer=Color.White,
 surfaceContainerHigh=CompanionPalette.Background,surfaceContainerHighest=CompanionPalette.Lavender,
 outline=CompanionPalette.Muted,outlineVariant=CompanionPalette.Lavender,
 inverseSurface=Color.Black,inverseOnSurface=Color.White,
 error=CompanionPalette.Burgundy,onError=Color.White,
 errorContainer=CompanionPalette.Rose,onErrorContainer=CompanionPalette.Burgundy,scrim=Color.Black
)
