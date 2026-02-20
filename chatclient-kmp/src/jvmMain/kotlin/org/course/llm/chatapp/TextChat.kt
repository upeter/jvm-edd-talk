package org.course.llm.chatapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ChatMessage(
    val content: String,
    val isUserMessage: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val customStyle: ChatBubbleStyle? = null,
    val sessionId: String? = null,
    val userRequest: String? = null,
    val feedbackStatus: FeedbackStatus = FeedbackStatus.IDLE,
    val lastFeedbackThumbsUp: Boolean? = null,
    val id: String = UUID.randomUUID().toString()
)

data class ChatInput(
    val message: String,
    val conversationId: String
)

data class FeedbackPayload(
    val request: String,
    val answer: String,
    val sessionId: String,
    val rating: String
)

data class MarkdownColors(
    val text: Color,
    val link: Color,
    val code: Color
)

data class TranscribedMessageReply(
    val transcribedInputText: String,
    val outputText: String
)

sealed class ChatBubbleStyle {
    abstract val alignment: Alignment
    abstract val backgroundColor: Color
    abstract val textColor: Color

    object User : ChatBubbleStyle() {
        override val alignment = Alignment.CenterEnd
        override val backgroundColor = Color.LightGray
        override val textColor = Color.DarkGray
    }

    object Agent : ChatBubbleStyle() {
        override val alignment = Alignment.CenterStart
        override val backgroundColor = Color(0xFFFF100D)
        override val textColor = Color.White
    }

    // Gray bubble for transcribed user input
    object Transcribed : ChatBubbleStyle() {
        override val alignment = Alignment.CenterEnd
        override val backgroundColor = Color.LightGray
        override val textColor = Color.DarkGray
    }
}

enum class FeedbackStatus {
    IDLE,
    SENDING,
    SENT,
    ERROR
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onFeedback: ((ChatMessage, Boolean) -> Unit)? = null
) {
    val defaultStyle = if (message.isUserMessage) ChatBubbleStyle.User else ChatBubbleStyle.Agent
    val style = message.customStyle ?: defaultStyle

    Column(modifier = Modifier.fillMaxWidth()) {
        ChatBubbleWithStyle(message.content, style)
        if (!message.isUserMessage && onFeedback != null) {
            FeedbackRow(message = message, onFeedback = onFeedback)
        }
    }
}

@Composable
fun MarkdownText(content: String, style: ChatBubbleStyle) {
    val markdownColors = MarkdownColors(
        text = style.textColor,
        link = style.textColor.copy(alpha = 0.85f),
        code = style.textColor.copy(alpha = 0.9f)
    )
    val annotated = remember(content, style) { buildMarkdownAnnotatedString(content, markdownColors) }
    CompositionLocalProvider(LocalContentColor provides style.textColor) {
        SelectionContainer {
            Text(
                text = annotated,
                modifier = Modifier.padding(12.dp),
                color = style.textColor,
                style = TextStyle.Default
            )
        }
    }
}

@Composable
fun ChatBubbleWithStyle(content: String, style: ChatBubbleStyle) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
        contentAlignment = style.alignment
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (style is ChatBubbleStyle.Agent) Arrangement.Start else Arrangement.End
        ) {
            // Show agent icon only for agent messages
            if (style is ChatBubbleStyle.Agent) {
                Image(
                    painter = painterResource("AgentIcon.png"),
                    contentDescription = "Agent",
                    modifier = Modifier.size(60.dp).padding(end = 8.dp),
                    alignment = Alignment.TopStart
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = style.backgroundColor,
                modifier = Modifier.widthIn(max = 400.dp)
            ) {
                MarkdownText(content, style)
            }
        }
    }
}

@Composable
fun FeedbackRow(
    message: ChatMessage,
    onFeedback: (ChatMessage, Boolean) -> Unit
) {
    val isSending = message.feedbackStatus == FeedbackStatus.SENDING
    val sentUp = message.feedbackStatus == FeedbackStatus.SENT && message.lastFeedbackThumbsUp == true
    val sentDown = message.feedbackStatus == FeedbackStatus.SENT && message.lastFeedbackThumbsUp == false
    val upColor = when {
        isSending -> Color.Gray.copy(alpha = 0.6f)
        sentUp -> Color(0xFF4CAF50)
        else -> Color.Gray
    }
    val downColor = when {
        isSending -> Color.Gray.copy(alpha = 0.6f)
        sentDown -> Color(0xFFF44336)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!message.isUserMessage) {
            Spacer(modifier = Modifier.width(68.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onFeedback(message, true) },
                enabled = !isSending,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ThumbUp,
                    contentDescription = "Thumb up",
                    tint = upColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = { onFeedback(message, false) },
                enabled = !isSending,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ThumbDown,
                    contentDescription = "Thumb down",
                    tint = downColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            val statusLabel = when (message.feedbackStatus) {
                FeedbackStatus.SENDING -> "Sending..."
                FeedbackStatus.SENT -> "Thank you for your feedback!"
                FeedbackStatus.ERROR -> "Send failed"
                else -> null
            }

            statusLabel?.let {
                Text(
                    text = it,
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private fun buildMarkdownAnnotatedString(text: String, colors: MarkdownColors): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var inCodeBlock = false

    val lines = text.lines()
    lines.forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd()

        if (line.startsWith("```")) {
            inCodeBlock = !inCodeBlock
            if (!inCodeBlock) {
                builder.append('\n')
            }
        } else if (inCodeBlock) {
            builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code))
            builder.append(line)
            builder.pop()
            builder.append('\n')
        } else {
            when {
                line.startsWith("# ") -> builder.appendStyledLine(line.removePrefix("# ").trim(), FontWeight.Bold, 20, colors)
                line.startsWith("## ") -> builder.appendStyledLine(line.removePrefix("## ").trim(), FontWeight.Bold, 18, colors)
                line.startsWith("### ") -> builder.appendStyledLine(line.removePrefix("### ").trim(), FontWeight.SemiBold, 16, colors)
                line.startsWith("- ") || line.startsWith("* ") -> builder.appendBulletLine(line.drop(2).trim(), colors)
                line.matches(Regex("\\d+\\. .*")) -> builder.appendNumberedLine(line, colors)
                line.isBlank() -> builder.append('\n')
                else -> builder.appendInlineText(line, colors)
            }
        }

        if (index != lines.lastIndex) {
            builder.append('\n')
        }
    }

    return builder.toAnnotatedString()
}

private fun AnnotatedString.Builder.appendStyledLine(
    content: String,
    weight: FontWeight,
    sizeSp: Int,
    colors: MarkdownColors
) {
    pushStyle(SpanStyle(fontWeight = weight, fontSize = sizeSp.sp, color = colors.text))
    appendInlineText(content, colors)
    pop()
}

private fun AnnotatedString.Builder.appendBulletLine(content: String, colors: MarkdownColors) {
    append("• ")
    appendInlineText(content, colors)
}

private fun AnnotatedString.Builder.appendNumberedLine(line: String, colors: MarkdownColors) {
    val numberEnd = line.indexOf('.')
    val number = line.substring(0, numberEnd).trim()
    val body = line.substring(numberEnd + 1).trim()
    append("$number. ")
    appendInlineText(body, colors)
}

private fun AnnotatedString.Builder.appendInlineText(text: String, colors: MarkdownColors) {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.text))
                    appendInlineText(text.substring(index + 2, end), colors)
                    pop()
                    index = end + 2
                    continue
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = colors.text))
                    appendInlineText(text.substring(index + 1, end), colors)
                    pop()
                    index = end + 1
                    continue
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code))
                    append(text.substring(index + 1, end))
                    pop()
                    index = end + 1
                    continue
                }
            }
            text[index] == '[' -> {
                val endText = text.indexOf(']', startIndex = index + 1)
                if (endText != -1 && endText + 1 < text.length && text[endText + 1] == '(') {
                    val endLink = text.indexOf(')', startIndex = endText + 2)
                    if (endLink != -1) {
                        val label = text.substring(index + 1, endText)
                        val url = text.substring(endText + 2, endLink)
                        pushStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                        append(label)
                        pop()
                        index = endLink + 1
                        continue
                    }
                }
            }
        }

        append(text[index])
        index++
    }
}

@Composable
fun TextChatScreen(httpClient: HttpClient, conversationId: String) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Recorder + overlay states
    var showRecordOverlay by remember(conversationId) { mutableStateOf(false) }
    var isRecording by remember(conversationId) { mutableStateOf(false) }

    // Animation for record button in overlay
    val recordScale by animateFloatAsState(
        targetValue = if (isRecording) 1.2f else 1f,
        animationSpec = if (isRecording) {
            infiniteRepeatable(
                animation = tween(durationMillis = 500),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(durationMillis = 300)
        },
        label = "recordButtonScale"
    )

    // Reset all UI state when the conversationId changes so previous messages disappear
    var inputText by remember(conversationId) { mutableStateOf("") }
    var messages by remember(conversationId) { mutableStateOf(listOf<ChatMessage>()) }
    var isLoading by remember(conversationId) { mutableStateOf(false) }

    val audioRecorder = remember(conversationId) { AudioRecorder() }
    DisposableEffect(audioRecorder) {
        onDispose {
            audioRecorder.cleanup()
        }
    }

    val sendFeedback: (ChatMessage, Boolean) -> Unit = feedback@{ targetMessage, isThumbsUp ->
        if (targetMessage.feedbackStatus == FeedbackStatus.SENDING) return@feedback
        val requestText = targetMessage.userRequest
        val session = targetMessage.sessionId
        if (requestText.isNullOrBlank() || session.isNullOrBlank()) return@feedback

        messages = messages.map {
            if (it.id == targetMessage.id) {
                it.copy(
                    feedbackStatus = FeedbackStatus.SENDING,
                    lastFeedbackThumbsUp = isThumbsUp
                )
            } else it
        }

        scope.launch {
            try {
                httpClient.post("http://localhost:8082/feedback") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        FeedbackPayload(
                            request = requestText,
                            answer = targetMessage.content,
                            sessionId = session,
                            rating = if (isThumbsUp) "UP" else "DOWN"
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    messages = messages.map {
                        if (it.id == targetMessage.id) it.copy(feedbackStatus = FeedbackStatus.SENT) else it
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    messages = messages.map {
                        if (it.id == targetMessage.id) it.copy(feedbackStatus = FeedbackStatus.ERROR) else it
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
        // Progress bar
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = Color(0xFFFF100D),
                trackColor = Color(0xFFFF100D).copy(alpha = 0.3f)
            )
        }

        // Chat messages area
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message, onFeedback = sendFeedback)
            }
        }

        // Input area
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Create focus requesters for input field and send button
            val inputFieldFocus = remember { FocusRequester() }
            val sendButtonFocus = remember { FocusRequester() }

            // Function to send message
            val sendMessage = {
                if (inputText.isNotBlank() && !isLoading) {
                    val textToSend = inputText
                    // Clear input immediately after sending so the user sees an empty box
                    inputText = ""

                    val userMessage = ChatMessage(textToSend, true)
                    messages = messages + userMessage
                    // Immediately scroll to the newly added user message so it's visible
                    scope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                    isLoading = true

                    scope.launch {
                        try {
                            val response = httpClient.post("http://localhost:8082/chat") {
                                contentType(ContentType.Application.Json)
                                setBody(ChatInput(textToSend, conversationId))
                            }

                            val responseText = response.body<String>()
                            messages = messages + ChatMessage(
                                responseText,
                                isUserMessage = false,
                                sessionId = conversationId,
                                userRequest = textToSend
                            )

                            // Scroll to the bottom
                            listState.animateScrollToItem(messages.size - 1)
                        } catch (e: Exception) {
                            messages = messages + ChatMessage("Error: ${e.message}", false)
                        } finally {
                            isLoading = false
                            // Return focus to input field after sending
                            inputFieldFocus.requestFocus()
                        }
                    }
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, bottom = 8.dp)
                    .focusRequester(inputFieldFocus)
                    .focusProperties {
                        next = sendButtonFocus
                    }
                    .onKeyEvent { event ->
                        when (event.key) {
                            Key.Tab -> {
                                // Move focus to send button when Tab is pressed
                                sendButtonFocus.requestFocus()
                                true // Consume the event to prevent default behavior
                            }
                            Key.Enter -> {
                                // Send message when Enter is pressed
                                sendMessage()
                                true // Consume the event
                            }
                            else -> false // Don't consume other key events
                        }
                    },
                placeholder = { Text("Type a message...") },
                maxLines = 3
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { sendMessage() },
                modifier = Modifier
                    .padding(end = 8.dp, bottom = 8.dp)
                    .focusRequester(sendButtonFocus)
                    .focusProperties {
                        previous = inputFieldFocus
                    },
                enabled = !isLoading && inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF100D))
            ) {
                Text("Send")
            }

            Button(
                onClick = {
                    if (!isLoading) {
                        // Show overlay and start recording immediately
                        showRecordOverlay = true
                        if (!isRecording) {
                            isRecording = true
                            audioRecorder.startRecording()
                        }
                    }
                },
                modifier = Modifier
                    .padding(end = 8.dp, bottom = 8.dp),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF100D))
            ) {
                Text("Rec")
            }
        }

        }

        // Recording overlay
        if (showRecordOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Big circular record/stop button
                    Box(
                        modifier = Modifier
                            .size(120.dp * recordScale)
                            .clip(CircleShape)
                            .background(if (isRecording) Color(0xFFFF100D) else Color.LightGray)
                            .clickable {
                                if (!isRecording) {
                                    isRecording = true
                                    audioRecorder.startRecording()
                                } else {
                                    // Stop and send
                                    isRecording = false
                                    showRecordOverlay = false
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            val response = uploadRecording(
                                                audioRecorder = audioRecorder,
                                                httpClient = httpClient,
                                                url = "http://localhost:8082/audio-in-text-out-chat",
                                                conversationId = conversationId
                                            )

                                            if (response.status.value != 200) {
                                                throw Exception("Server returned error: ${response.status.value} ${response.status.description}")
                                            }
                                            val reply = response.body<TranscribedMessageReply>()

                                            messages = messages + ChatMessage(
                                                reply.transcribedInputText,
                                                isUserMessage = true,
                                                customStyle = ChatBubbleStyle.Transcribed
                                            )
                                            messages = messages + ChatMessage(
                                                reply.outputText,
                                                isUserMessage = false,
                                                sessionId = conversationId,
                                                userRequest = reply.transcribedInputText
                                            )

                                            listState.animateScrollToItem(messages.size - 1)
                                        } catch (e: Exception) {
                                            messages = messages + ChatMessage("Error: ${e.message}", false)
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isRecording) "Stop" else "Record", color = Color.White)
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = {
                                // Cancel overlay
                                if (isRecording) {
                                    isRecording = false
                                    audioRecorder.stopRecording()
                                }
                                showRecordOverlay = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.DarkGray,
                                contentColor = Color.White
                            )
                        ) { Text("Cancel") }
                    }
                }
            }
        }
    }
}
