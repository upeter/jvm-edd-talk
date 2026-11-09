package org.course.llm.chatapp

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette taken from the rendered https://jfall.nl/timetable-2026/ page
object JFallColors {
    val Background = Color(0xFFFFFFFF)      // page body
    val Surface = Color(0xFFFFFFFF)
    val SurfaceRaised = Color(0xFFF5F5F5)
    val Border = Color(0xFFE2E2E2)          // grid lines between timetable rows
    val Orange = Color(0xFFFF9E0E)          // session cards, buttons, active nav, times, day heading
    val OrangeDark = Color(0xFFCC7E0B)      // plenary sessions (Opening, keynotes)
    val Red = Color(0xFFE30613)             // banner gradient start
    val GradientEnd = Color(0xFFF38F00)     // banner gradient end
    val Navy = Color(0xFF04131B)            // text on the "Contact" button
    val Text = Color(0xFF111111)            // room labels, nav
    val TextMuted = Color(0xFF6B6B6B)
    val OnAccent = Color(0xFFFFFFFF)        // text on orange cards and buttons
    val Error = Color(0xFFE30613)

    /** The red → orange gradient of the timetable banner. */
    val Gradient = Brush.horizontalGradient(listOf(Red, GradientEnd))
}

val InterFamily = FontFamily(
    Font("fonts/Inter-Regular.ttf", FontWeight.Normal),
    Font("fonts/Inter-Medium.ttf", FontWeight.Medium),
    Font("fonts/Inter-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/Inter-Bold.ttf", FontWeight.Bold),
    Font("fonts/Inter-Black.ttf", FontWeight.Black),
)

private val JFallTypography = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = InterFamily)
    Typography(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
}

private val JFallColorScheme = lightColorScheme(
    primary = JFallColors.Orange,
    onPrimary = JFallColors.OnAccent,
    secondary = JFallColors.OrangeDark,
    onSecondary = JFallColors.OnAccent,
    background = JFallColors.Background,
    onBackground = JFallColors.Text,
    surface = JFallColors.Surface,
    onSurface = JFallColors.Text,
    surfaceVariant = JFallColors.SurfaceRaised,
    onSurfaceVariant = JFallColors.TextMuted,
    surfaceContainer = JFallColors.Surface,
    surfaceContainerHigh = JFallColors.Surface,
    surfaceContainerHighest = JFallColors.SurfaceRaised,
    outline = JFallColors.Border,
    error = JFallColors.Error,
)

@Composable
fun JFallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JFallColorScheme, typography = JFallTypography, content = content)
}

/** Draws a coloured stripe along the top edge, on top of the content's background. */
fun Modifier.topStripe(color: Color = JFallColors.Orange, width: Dp = 3.dp) = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
}

/** White tag with dark bold text, like the "ROOM 9" tags on the timetable's session cards. */
@Composable
fun RoomTag(text: String, bordered: Boolean = false) {
    Text(
        text = text.uppercase(),
        color = JFallColors.Text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White)
            .then(if (bordered) Modifier.border(1.dp, JFallColors.Border, RoundedCornerShape(2.dp)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** Duke on a white tile with a light border. */
@Composable
fun DukeAvatar(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = height * 0.75f, height = height)
            .clip(RoundedCornerShape(height * 0.12f))
            .background(Color.White)
            .border(1.dp, JFallColors.Border, RoundedCornerShape(height * 0.12f))
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

// jfall.nl buttons: orange pill (radius 50px), uppercase bold 14px, padding 12px 24px
val JFallButtonShape = RoundedCornerShape(50)
val JFallButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

/** Primary button ("Pre-conference ticket"): solid orange with white text. */
@Composable
fun jfallButtonColors() = ButtonDefaults.buttonColors(
    containerColor = JFallColors.Orange,
    contentColor = JFallColors.OnAccent,
    disabledContainerColor = JFallColors.SurfaceRaised,
    disabledContentColor = JFallColors.TextMuted,
)

/** Secondary button: the site's hover state, transparent with orange text and an orange border. */
@Composable
fun jfallSecondaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = JFallColors.Orange,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = JFallColors.TextMuted,
)

@Composable
fun jfallSecondaryButtonBorder(enabled: Boolean = true) =
    BorderStroke(1.dp, if (enabled) JFallColors.Orange else JFallColors.Border)

@Composable
fun JFallButtonLabel(text: String) {
    Text(text.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
}

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
