package com.pleos.cardex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 차량 디스플레이: 어두운 배경 + 큰 글씨 + 높은 대비
private val Colors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    secondary = Color(0xFFFFC94D),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
)

private val CarTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 22.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
)

fun rarityColor(rarity: String): Color = when (rarity) {
    "legendary" -> Color(0xFFFFB300)
    "rare" -> Color(0xFF7C4DFF)
    else -> Color(0xFF4DA3FF)
}

@Composable
fun CarDexTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Colors, typography = CarTypography, content = content)
