package org.course.llm.chatapp

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import java.util.*

// Enum to represent different screens in the app
enum class Screen {
    TEXT_CHAT,
    AUDIO_CHAT
}

@Composable
@Preview
fun App() {
    var conversationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(Screen.TEXT_CHAT) }

    val httpClient = remember {
        HttpClient(CIO) {
            engine {
                requestTimeout = 30_000 // 30 seconds
            }
            install(ContentNegotiation) {
                jackson()
            }
        }
    }

    DevoxxTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(DevoxxColors.Background)
        ) {
            // Header bar, styled after the devoxx.be navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DevoxxColors.Surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = DevoxxColors.Text
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = DevoxxColors.SurfaceRaised
                    ) {
                        DropdownMenuItem(
                            text = { Text("Text Chat") },
                            onClick = {
                                currentScreen = Screen.TEXT_CHAT
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Audio Chat") },
                            onClick = {
                                currentScreen = Screen.AUDIO_CHAT
                                menuExpanded = false
                            }
                        )
                        HorizontalDivider(color = DevoxxColors.Border)
                        DropdownMenuItem(
                            text = { Text("Clear Conversation") },
                            onClick = {
                                conversationId = UUID.randomUUID().toString()
                                menuExpanded = false
                            }
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))
                DukeAvatar(height = 36.dp)
                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = DevoxxColors.Text)) { append("DEVOXX ") }
                            withStyle(SpanStyle(color = DevoxxColors.Orange)) { append("BELGIUM") }
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "FROM DEVELOPER TO BUILDER",
                        color = DevoxxColors.TextMuted,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp
                    )
                }

                Spacer(Modifier.weight(1f))

                ScreenTab("Text Chat", currentScreen == Screen.TEXT_CHAT) { currentScreen = Screen.TEXT_CHAT }
                Spacer(Modifier.width(8.dp))
                ScreenTab("Audio Chat", currentScreen == Screen.AUDIO_CHAT) { currentScreen = Screen.AUDIO_CHAT }
            }
            HorizontalDivider(color = DevoxxColors.Border)

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DevoxxPill("Attendee Assistant")
                Spacer(Modifier.width(8.dp))
                DevoxxPill("Antwerp 2026", color = DevoxxColors.Cyan)
            }

            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Screen content based on current screen
                when (currentScreen) {
                    Screen.TEXT_CHAT -> {
                        // Display the text chat screen
                        TextChatScreen(httpClient, conversationId)
                    }

                    Screen.AUDIO_CHAT -> {
                        // Display the audio chat screen
                        AudioChatScreen(httpClient, conversationId)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> DevoxxColors.Orange
        hovered -> DevoxxColors.Lime
        else -> Color.Transparent
    }
    val foreground = if (selected || hovered) DevoxxColors.OnAccent else DevoxxColors.Text
    Text(
        text = label.uppercase(),
        color = foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(96.dp))
            .background(background)
            .border(1.dp, if (selected) DevoxxColors.Orange else DevoxxColors.Border, RoundedCornerShape(96.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Devoxx Belgium 2026 · AI Assistant",
        icon = painterResource("AgentIconDuke.png"),
        state = rememberWindowState(width = 960.dp, height = 720.dp)
    ) {
        App()
    }
}
