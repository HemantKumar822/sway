package com.sway.designui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sway.designui.R

/**
 * Type system (story 9.1, UX §7.2): Outfit carries display/headline warmth,
 * Inter carries everything else with screen-native neutrality. Both bundled
 * (variable, OFL). The ramp: Display 44 -> Headline 24 -> Title 20/16 ->
 * Body 16/14 -> Label 14/12; numerics get tabular figures so scrubbers and
 * timestamps never jitter.
 */
object SwayFonts {
    val Outfit = FontFamily(
        Font(R.font.outfit_variable, weight = FontWeight.Normal),
        Font(R.font.outfit_variable, weight = FontWeight.Medium),
        Font(R.font.outfit_variable, weight = FontWeight.SemiBold),
        Font(R.font.outfit_variable, weight = FontWeight.Bold),
    )

    val Inter = FontFamily(
        Font(R.font.inter_variable, weight = FontWeight.Normal),
        Font(R.font.inter_variable, weight = FontWeight.Medium),
        Font(R.font.inter_variable, weight = FontWeight.SemiBold),
    )
}

/** `tnum` for every duration/position/counter (UX §7.2 law). */
private fun tabular(base: TextStyle): TextStyle = base.copy(
    fontFeatureSettings = "tnum",
)

object SwayType {

    val Display = TextStyle(
        fontFamily = SwayFonts.Outfit,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.5).sp,
    )

    val Headline = TextStyle(
        fontFamily = SwayFonts.Outfit,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    )

    val TitleLg = TextStyle(
        fontFamily = SwayFonts.Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    )

    val TitleMd = TextStyle(
        fontFamily = SwayFonts.Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    val BodyLg = TextStyle(
        fontFamily = SwayFonts.Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    val BodyMd = TextStyle(
        fontFamily = SwayFonts.Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    val LabelLg = TextStyle(
        fontFamily = SwayFonts.Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )

    val LabelMd = TextStyle(
        fontFamily = SwayFonts.Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
}

/** M3 mapping with the tabular-figure style applied wherever numbers flow. */
fun swayTypography(): Typography = Typography(
    displayLarge = SwayType.Display,
    displayMedium = SwayType.Display.copy(fontSize = 36.sp, lineHeight = 42.sp),
    headlineLarge = SwayType.Headline,
    headlineMedium = SwayType.Headline.copy(fontSize = 22.sp),
    titleLarge = tabular(SwayType.TitleLg),
    titleMedium = tabular(SwayType.TitleMd),
    bodyLarge = SwayType.BodyLg,
    bodyMedium = SwayType.BodyMd,
    labelLarge = tabular(SwayType.LabelLg),
    labelMedium = tabular(SwayType.LabelMd),
)
