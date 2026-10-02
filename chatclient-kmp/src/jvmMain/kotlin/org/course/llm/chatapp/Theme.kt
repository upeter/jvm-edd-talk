package org.course.llm.chatapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette taken from devoxx.be (2026 site)
object DevoxxColors {
    val Background = Color(0xFF0B121A)
    val Surface = Color(0xFF0E1621)
    val SurfaceRaised = Color(0xFF16212E)
    val Border = Color(0xFF232A3A)
    val Orange = Color(0xFFEA9635)
    val Lime = Color(0xFFD0FF00)
    val Cyan = Color(0xFF70E7FD)
    val Text = Color(0xFFFAFAFA)
    val TextMuted = Color(0xFF8A97A6)
    val OnAccent = Color(0xFF1F1F1F)
    val Success = Color(0xFF7ED957)
    val Error = Color(0xFFEA384C)
}

private val DevoxxColorScheme = darkColorScheme(
    primary = DevoxxColors.Orange,
    onPrimary = DevoxxColors.OnAccent,
    secondary = DevoxxColors.Lime,
    onSecondary = DevoxxColors.OnAccent,
    tertiary = DevoxxColors.Cyan,
    background = DevoxxColors.Background,
    onBackground = DevoxxColors.Text,
    surface = DevoxxColors.Surface,
    onSurface = DevoxxColors.Text,
    surfaceVariant = DevoxxColors.SurfaceRaised,
    onSurfaceVariant = DevoxxColors.TextMuted,
    surfaceContainerHigh = DevoxxColors.SurfaceRaised,
    outline = DevoxxColors.Border,
    error = DevoxxColors.Error,
)

@Composable
fun DevoxxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DevoxxColorScheme, content = content)
}

/** Orange, uppercase, letter-spaced pill — the "subtitle" style used across devoxx.be. */
@Composable
fun DevoxxPill(text: String, color: Color = DevoxxColors.Orange) {
    Text(
        text = text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = Modifier
            .border(1.dp, DevoxxColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

/** Duke on a light tile so his black outline stays visible on the dark background. */
@Composable
fun DukeAvatar(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = height * 0.75f, height = height)
            .clip(RoundedCornerShape(height * 0.18f))
            .background(DevoxxColors.Text)
            .padding(height * 0.08f),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource("AgentIconDuke.png"),
            contentDescription = "Agent",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun devoxxButtonColors() = ButtonDefaults.buttonColors(
    containerColor = DevoxxColors.Orange,
    contentColor = DevoxxColors.OnAccent,
    disabledContainerColor = DevoxxColors.SurfaceRaised,
    disabledContentColor = DevoxxColors.TextMuted,
)

@Composable
fun devoxxTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = DevoxxColors.Orange,
    unfocusedBorderColor = DevoxxColors.Border,
    cursorColor = DevoxxColors.Orange,
    focusedTextColor = DevoxxColors.Text,
    unfocusedTextColor = DevoxxColors.Text,
    focusedContainerColor = DevoxxColors.Surface,
    unfocusedContainerColor = DevoxxColors.Surface,
    focusedPlaceholderColor = DevoxxColors.TextMuted,
    unfocusedPlaceholderColor = DevoxxColors.TextMuted,
    focusedLabelColor = DevoxxColors.Orange,
)
