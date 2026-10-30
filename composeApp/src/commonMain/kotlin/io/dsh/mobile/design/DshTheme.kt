package io.dsh.mobile.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dsh.mobile.features.NavigationIcon

private val Light = lightColorScheme(
    primary = Color(0xFF355CE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBF0FF), onPrimaryContainer = Color(0xFF243C8C),
    secondary = Color(0xFF39465A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF0F6), onSecondaryContainer = Color(0xFF243C8C),
    tertiary = Color(0xFF176B4B), tertiaryContainer = Color(0xFFE8F5EF), onTertiaryContainer = Color(0xFF12553C),
    background = Color.White, onBackground = Color(0xFF182230),
    surface = Color.White, onSurface = Color(0xFF182230),
    surfaceDim = Color(0xFFE2E4EA), surfaceBright = Color.White, surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF4F5F8), surfaceContainerHigh = Color(0xFFECEEF2), surfaceContainerHighest = Color(0xFFE4E7ED),
    surfaceVariant = Color(0xFFF4F5F8), onSurfaceVariant = Color(0xFF5E6878),
    outline = Color(0xFF7B8595), outlineVariant = Color(0xFFDDE2EB),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA7BBFF), onPrimary = Color(0xFF10214F),
    primaryContainer = Color(0xFF243457), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBCC7DA), secondaryContainer = Color(0xFF273142), onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF8AD9B6), tertiaryContainer = Color(0xFF163E30), onTertiaryContainer = Color(0xFFA8E7C9),
    background = Color(0xFF10141C), onBackground = Color(0xFFEDF1F7),
    surface = Color(0xFF191F29), onSurface = Color(0xFFEDF1F7),
    surfaceDim = Color(0xFF10141C), surfaceBright = Color(0xFF303A49), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF0D1118), surfaceContainerLow = Color(0xFF191F29),
    surfaceContainer = Color(0xFF202835), surfaceContainerHigh = Color(0xFF283241), surfaceContainerHighest = Color(0xFF303A49),
    surfaceVariant = Color(0xFF252E3B), onSurfaceVariant = Color(0xFFB2BDCB),
    outline = Color(0xFF8491A4), outlineVariant = Color(0xFF394454),
)
private val Type = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}

@Composable
fun IconTile(name: String, modifier: Modifier = Modifier) {
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary) { Box(contentAlignment = Alignment.Center) { NavigationIcon(name) } }
}

@Composable
fun StatusPill(label: String, online: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50),
        color = if (online) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (online) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(6.dp), shape = RoundedCornerShape(50), color = LocalContentColor.current) {}
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
