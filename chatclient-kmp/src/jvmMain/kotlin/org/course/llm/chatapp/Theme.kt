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

// J-Fall assistant palette
object JFallColors {
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

private val JFallColorScheme = darkColorScheme(
    primary = JFallColors.Orange,
    onPrimary = JFallColors.OnAccent,
    secondary = JFallColors.Lime,
    onSecondary = JFallColors.OnAccent,
    tertiary = JFallColors.Cyan,
    background = JFallColors.Background,
    onBackground = JFallColors.Text,
    surface = JFallColors.Surface,
    onSurface = JFallColors.Text,
    surfaceVariant = JFallColors.SurfaceRaised,
    onSurfaceVariant = JFallColors.TextMuted,
    surfaceContainerHigh = JFallColors.SurfaceRaised,
    outline = JFallColors.Border,
    error = JFallColors.Error,
)

@Composable
fun JFallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JFallColorScheme, content = content)
}

/** Orange, uppercase, letter-spaced pill — the "subtitle" style used across the app. */
@Composable
fun JFallPill(text: String, color: Color = JFallColors.Orange) {
    Text(
        text = text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = Modifier
            .border(1.dp, JFallColors.Border, RoundedCornerShape(12.dp))
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
            .background(JFallColors.Text)
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
fun jfallButtonColors() = ButtonDefaults.buttonColors(
    containerColor = JFallColors.Orange,
    contentColor = JFallColors.OnAccent,
    disabledContainerColor = JFallColors.SurfaceRaised,
    disabledContentColor = JFallColors.TextMuted,
)

@Composable
fun jfallTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JFallColors.Orange,
    unfocusedBorderColor = JFallColors.Border,
    cursorColor = JFallColors.Orange,
    focusedTextColor = JFallColors.Text,
    unfocusedTextColor = JFallColors.Text,
    focusedContainerColor = JFallColors.Surface,
    unfocusedContainerColor = JFallColors.Surface,
    focusedPlaceholderColor = JFallColors.TextMuted,
    unfocusedPlaceholderColor = JFallColors.TextMuted,
    focusedLabelColor = JFallColors.Orange,
)
