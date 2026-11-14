package de.ovgu.imiq.companion

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** IMIQ house typeface, bundled for offline use (SIL Open Font License 1.1). */
internal val AppFont=FontFamily(
 Font(R.font.barlow_regular,FontWeight.Normal),
 Font(R.font.barlow_semibold,FontWeight.SemiBold),
 Font(R.font.barlow_bold,FontWeight.Bold),
 Font(R.font.barlow_italic,FontWeight.Normal,FontStyle.Italic)
)

internal fun companionTypography(large:Boolean):Typography {
 val extra=if(large)2 else 0
 fun style(size:Int,height:Int,weight:FontWeight=FontWeight.Normal)=TextStyle(
  fontFamily=AppFont,fontWeight=weight,fontSize=(size+extra).sp,
  lineHeight=(height+extra).sp,letterSpacing=0.sp)
 return Typography(
  displayLarge=style(36,44,FontWeight.SemiBold),
  displayMedium=style(32,40,FontWeight.SemiBold),
  displaySmall=style(28,36,FontWeight.SemiBold),
  headlineLarge=style(28,36,FontWeight.SemiBold),
  headlineMedium=style(24,32,FontWeight.SemiBold),
  headlineSmall=style(22,28,FontWeight.SemiBold),
  titleLarge=style(20,28,FontWeight.SemiBold),
  titleMedium=style(18,24,FontWeight.Medium),
  titleSmall=style(16,22,FontWeight.Medium),
  bodyLarge=style(16,24),
  bodyMedium=style(14,20),
  bodySmall=style(12,18),
  labelLarge=style(14,20,FontWeight.SemiBold),
  labelMedium=style(12,18,FontWeight.SemiBold),
  labelSmall=style(11,16,FontWeight.SemiBold))
}
