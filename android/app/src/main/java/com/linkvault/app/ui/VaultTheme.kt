package com.linkvault.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkvault.app.R

private val VaultFontFamily = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF30372D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EADF),
    onPrimaryContainer = Color(0xFF30372D),
    secondary = Color(0xFF62685C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEFE8),
    onSecondaryContainer = Color(0xFF41473B),
    tertiary = Color(0xFF675B48),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0EBE2),
    onTertiaryContainer = Color(0xFF4E4231),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF292F24),
    surface = Color(0xFFFAF9F6),
    onSurface = Color(0xFF292F24),
    surfaceVariant = Color(0xFFEFEFEA),
    onSurfaceVariant = Color(0xFF62685C),
    surfaceBright = Color(0xFFFEFDFB),
    surfaceDim = Color(0xFFDFE0D8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F4EF),
    surfaceContainer = Color(0xFFEFEFEA),
    surfaceContainerHigh = Color(0xFFE9EAE2),
    surfaceContainerHighest = Color(0xFFE3E5DB),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF7B8074),
    outlineVariant = Color(0xFFE0E2D9),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF641B16),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0E4D7),
    onPrimary = Color(0xFF292F24),
    primaryContainer = Color(0xFF383D32),
    onPrimaryContainer = Color(0xFFE0E4D7),
    secondary = Color(0xFFBFC4B5),
    onSecondary = Color(0xFF30352B),
    secondaryContainer = Color(0xFF30342C),
    onSecondaryContainer = Color(0xFFD5DACB),
    tertiary = Color(0xFFD1C6B5),
    onTertiary = Color(0xFF3A3023),
    tertiaryContainer = Color(0xFF39332B),
    onTertiaryContainer = Color(0xFFE7DCCD),
    background = Color(0xFF191B17),
    onBackground = Color(0xFFEEEFE9),
    surface = Color(0xFF191B17),
    onSurface = Color(0xFFEEEFE9),
    surfaceVariant = Color(0xFF2B2E27),
    onSurfaceVariant = Color(0xFFB9BEB0),
    surfaceBright = Color(0xFF393C34),
    surfaceDim = Color(0xFF141611),
    surfaceContainerLowest = Color(0xFF11130F),
    surfaceContainerLow = Color(0xFF1E211B),
    surfaceContainer = Color(0xFF252820),
    surfaceContainerHigh = Color(0xFF2B2E27),
    surfaceContainerHighest = Color(0xFF34382F),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF8D9483),
    outlineVariant = Color(0xFF383D32),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF622622),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val VaultTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(fontFamily = VaultFontFamily, fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = VaultFontFamily, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = VaultFontFamily, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = VaultFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp,
    ),
)

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = VaultTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
