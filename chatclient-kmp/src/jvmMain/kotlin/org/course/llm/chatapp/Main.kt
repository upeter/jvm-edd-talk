package org.course.llm.chatapp

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.style.TextAlign
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

    JFallTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(JFallColors.Background)
        ) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JFallColors.Background)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = JFallColors.Text
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = JFallColors.Surface
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
                        HorizontalDivider(color = JFallColors.Border)
                        DropdownMenuItem(
                            text = { Text("Clear Conversation") },
                            onClick = {
                                conversationId = UUID.randomUUID().toString()
                                menuExpanded = false
                            }
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))
                Image(
                    painter = painterResource("JFall2026Logo.png"),
                    contentDescription = "J-Fall 2026",
                    modifier = Modifier.height(52.dp)
                )

                Spacer(Modifier.weight(1f))

                // Nav like jfall.nl: uppercase bold black links, the current page in orange
                NavItem("Text Chat", currentScreen == Screen.TEXT_CHAT) { currentScreen = Screen.TEXT_CHAT }
                Spacer(Modifier.width(24.dp))
                NavItem("Audio Chat", currentScreen == Screen.AUDIO_CHAT) { currentScreen = Screen.AUDIO_CHAT }
                Spacer(Modifier.width(16.dp))
            }

            // Red -> orange banner with a white title, like "J-Fall 2026 Timetable"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JFallColors.Gradient)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "J-Fall 2026 Assistant",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Thursday 12 November 2026 · Pathé Ede",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
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
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = label.uppercase(),
        color = if (selected || hovered) JFallColors.Orange else JFallColors.Text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 8.dp)
    )
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "J-Fall 2026 · AI Assistant",
        icon = painterResource("AgentIconDuke.png"),
        state = rememberWindowState(width = 960.dp, height = 720.dp)
    ) {
        App()
    }
}
