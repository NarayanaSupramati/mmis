package network.mmis.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object MmisColors {
    val background=Color(0xFF0D1416)
    val surface=Color(0xFF152023)
    val elevated=Color(0xFF213034)
    val primaryText=Color(0xFFEAF1ED)
    val secondaryText=Color(0xFFABBAB4)
    val accent=Color(0xFFB8EACB)
    val success=Color(0xFF86D8AA)
    val warning=Color(0xFFEAC78A)
    val error=Color(0xFFFFB4AB)
    val glow=Color(0xFF1C3330)
    val outgoing=Color(0xFF254A38)
}
val mmisPalette=darkColorScheme(primary=MmisColors.accent,onPrimary=Color(0xFF10251B),
    primaryContainer=Color(0xFF294B3A),onPrimaryContainer=Color(0xFFD5F4DF),
    secondaryContainer=Color(0xFF294138),onSecondaryContainer=Color(0xFFD5F4DF),
    background=MmisColors.background,surface=MmisColors.surface,surfaceVariant=MmisColors.elevated,
    onSurface=MmisColors.primaryText,onSurfaceVariant=MmisColors.secondaryText,outline=Color(0xFF4C6258),error=MmisColors.error)
object MmisMotion { const val FADE_MS=220 }
@Composable fun MmisTheme(content:@Composable ()->Unit) { MaterialTheme(colorScheme=mmisPalette,content=content) }
/** One static native gradient shared by launch and every product destination. */
@Composable fun MmisBackground(content:@Composable BoxScope.()->Unit) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MmisColors.glow,MmisColors.background,MmisColors.background))),content=content)
}
