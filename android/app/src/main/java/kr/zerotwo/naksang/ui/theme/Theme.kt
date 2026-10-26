package kr.zerotwo.naksang.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kr.zerotwo.naksang.R

object NaksangColors {
    val Bg = Color(0xFFF5F2EA)
    val Surface = Color(0xFFFFFFFF)
    val Text = Color(0xFF1E2721)
    val Sub = Color(0xFF4A554D)
    val Border = Color(0xFFD6D1C4)
    val Btn = Color(0xFF35634A)
    val BtnText = Color(0xFFFFFFFF)
    val Ok = Color(0xFF2E6B3E)
    val OkBg = Color(0xFFE3F0E4)
    val Warn = Color(0xFF7F5200)
    val WarnBg = Color(0xFFFBEFD6)
    val Danger = Color(0xFFB3261E)
    val Link = Color(0xFF1F5E8C)
    val Sage600 = Color(0xFF305742)
    val Sage300 = Color(0xFF9FB2A2)
    val Sage150 = Color(0xFFD2D8CD)
    val Clay500 = Color(0xFFB79960)
    val Clay250 = Color(0xFFE0CCA7)
    val Sky = Color(0xFFDBE5ED)
    val Paper = Color(0xFFFAF8F4)
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

val Gmarket = FontFamily(
    Font(R.font.gmarket_sans_medium, FontWeight.Medium),
    Font(R.font.gmarket_sans_bold, FontWeight.Bold),
)

fun displayStyle(size: TextUnit, color: Color = NaksangColors.Text) = TextStyle(
    fontFamily = Gmarket,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    lineHeight = size * 1.12f,
    letterSpacing = (-0.03).em,
    color = color,
)

private val typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.01).em,
        color = NaksangColors.Text,
    ),
    bodyMedium = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.01).em,
        color = NaksangColors.Text,
    ),
    labelLarge = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.02.em,
        color = NaksangColors.Sub,
    ),
)

private val scheme = lightColorScheme(
    primary = NaksangColors.Btn,
    onPrimary = NaksangColors.BtnText,
    background = NaksangColors.Bg,
    onBackground = NaksangColors.Text,
    surface = NaksangColors.Surface,
    onSurface = NaksangColors.Text,
    secondary = NaksangColors.Ok,
    error = NaksangColors.Danger,
)

@Composable
fun NaksangTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
