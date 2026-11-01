package com.lagradost.cloudstream4.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.productsans_black
import com.lagradost.cloudstream4.generated.resources.productsans_blackitalic
import com.lagradost.cloudstream4.generated.resources.productsans_bold
import com.lagradost.cloudstream4.generated.resources.productsans_bolditalic
import com.lagradost.cloudstream4.generated.resources.productsans_italic
import com.lagradost.cloudstream4.generated.resources.productsans_light
import com.lagradost.cloudstream4.generated.resources.productsans_lightitalic
import com.lagradost.cloudstream4.generated.resources.productsans_medium
import com.lagradost.cloudstream4.generated.resources.productsans_mediumitalic
import com.lagradost.cloudstream4.generated.resources.productsans_regular
import com.lagradost.cloudstream4.generated.resources.productsans_thin
import com.lagradost.cloudstream4.generated.resources.productsans_thinitalic
import org.jetbrains.compose.resources.Font

object AppFont {
    val googleSans: FontFamily
        @Composable get() {
            val thin = Font(Res.font.productsans_thin, weight = FontWeight.W100, style = FontStyle.Normal)
            val thinItalic = Font(Res.font.productsans_thinitalic, weight = FontWeight.W100, style = FontStyle.Italic)
            val light = Font(Res.font.productsans_light, weight = FontWeight.W300, style = FontStyle.Normal)
            val lightItalic = Font(Res.font.productsans_lightitalic, weight = FontWeight.W300, style = FontStyle.Italic)
            val regular = Font(Res.font.productsans_regular, weight = FontWeight.W400, style = FontStyle.Normal)
            val italic = Font(Res.font.productsans_italic, weight = FontWeight.W400, style = FontStyle.Italic)
            val medium = Font(Res.font.productsans_medium, weight = FontWeight.W500, style = FontStyle.Normal)
            val mediumItalic = Font(Res.font.productsans_mediumitalic, weight = FontWeight.W500, style = FontStyle.Italic)
            val bold = Font(Res.font.productsans_bold, weight = FontWeight.W700, style = FontStyle.Normal)
            val boldItalic = Font(Res.font.productsans_bolditalic, weight = FontWeight.W700, style = FontStyle.Italic)
            val black = Font(Res.font.productsans_black, weight = FontWeight.W900, style = FontStyle.Normal)
            val blackItalic = Font(Res.font.productsans_blackitalic, weight = FontWeight.W900, style = FontStyle.Italic)
            return remember(thin, thinItalic, light, lightItalic, regular, italic, medium, mediumItalic, bold, boldItalic, black, blackItalic) {
                FontFamily(thin, thinItalic, light, lightItalic, regular, italic, medium, mediumItalic, bold, boldItalic, black, blackItalic)
            }
        }

    private val defaultTypography = androidx.compose.material3.Typography()
    val typography: Typography
        @Composable get() {
            val fontFamily = googleSans
            return remember(fontFamily) {
                val lineHeight = 1.3.em
                Typography(
                    displayLarge = defaultTypography.displayLarge.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    displayMedium = defaultTypography.displayMedium.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    displaySmall = defaultTypography.displaySmall.copy(fontFamily = fontFamily, lineHeight = lineHeight),

                    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = fontFamily, lineHeight = lineHeight),

                    titleLarge = defaultTypography.titleLarge.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    titleMedium = defaultTypography.titleMedium.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    titleSmall = defaultTypography.titleSmall.copy(fontFamily = fontFamily, lineHeight = lineHeight),

                    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    bodySmall = defaultTypography.bodySmall.copy(fontFamily = fontFamily, lineHeight = lineHeight),

                    labelLarge = defaultTypography.labelLarge.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    labelMedium = defaultTypography.labelMedium.copy(fontFamily = fontFamily, lineHeight = lineHeight),
                    labelSmall = defaultTypography.labelSmall.copy(fontFamily = fontFamily, lineHeight = lineHeight)
                )
            }
        }
}
