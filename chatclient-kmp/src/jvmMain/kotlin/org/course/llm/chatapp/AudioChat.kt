package org.course.llm.chatapp

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.sound.sampled.*
import javazoom.jl.player.Player
import kotlin.io.readBytes

/**
 * Audio recorder class that handles recording audio using Java Sound API
 */
class AudioRecorder {
    private val audioFormat = AudioFormat(44100f, 16, 1, true, false)
    private var targetDataLine: TargetDataLine? = null
    private var recording = false
    private val byteArrayOutputStream = ByteArrayOutputStream()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null

    fun cleanup() {
        recording = false
        recordingJob?.cancel()
        recordingJob = null
        targetDataLine?.stop()
        targetDataLine?.close()
        coroutineScope.cancel()
    }

    fun startRecording() {
        try {
            // Reset buffer for a fresh recording
            byteArrayOutputStream.reset()

            val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
            targetDataLine = AudioSystem.getLine(info) as TargetDataLine
            targetDataLine?.open(audioFormat)
            targetDataLine?.start()

            recording = true

            // Start recording in a coroutine
            recordingJob = coroutineScope.launch {
                val data = ByteArray(targetDataLine!!.bufferSize / 5)
                while (recording && isActive) {
                    val count = targetDataLine!!.read(data, 0, data.size)
                    if (count > 0) {
                        byteArrayOutputStream.write(data, 0, count)
                    }
                }
                byteArrayOutputStream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRecording(): ByteArray {
        recording = false
        recordingJob?.cancel()
        recordingJob = null
        targetDataLine?.stop()
        targetDataLine?.close()

        // Convert to MP3 format using lame command-line tool
        return convertToMp3(byteArrayOutputStream.toByteArray())
    }

    private fun convertToMp3(pcmData: ByteArray): ByteArray {
        return try {
            // Create temporary files for input and output
            val tempWavFile = File.createTempFile("recording", ".wav")
            val tempMp3File = File.createTempFile("recording", ".mp3")

            try {
                // Write WAV header and PCM data to temporary WAV file
                FileOutputStream(tempWavFile).use { output ->
                    // WAV header constants
                    val sampleRate = 44100
                    val channels = 1
                    val bitsPerSample = 16
                    val dataSize = pcmData.size
                    val format = 1 // PCM
                    val blockAlign = channels * bitsPerSample / 8
                    val byteRate = sampleRate * blockAlign

                    // Write WAV header
                    output.use {
                        // RIFF header
                        it.write("RIFF".toByteArray())
                        it.write(intToByteArray(36 + dataSize)) // File size - 8
                        it.write("WAVE".toByteArray())

                        // fmt chunk
                        it.write("fmt ".toByteArray())
                        it.write(intToByteArray(16)) // Chunk size
                        it.write(shortToByteArray(format.toShort())) // Format
                        it.write(shortToByteArray(channels.toShort())) // Channels
                        it.write(intToByteArray(sampleRate)) // Sample rate
                        it.write(intToByteArray(byteRate)) // Byte rate
                        it.write(shortToByteArray(blockAlign.toShort())) // Block align
                        it.write(shortToByteArray(bitsPerSample.toShort())) // Bits per sample

                        // data chunk
                        it.write("data".toByteArray())
                        it.write(intToByteArray(dataSize)) // Chunk size
                        it.write(pcmData) // Audio data
                    }
                }

                // Convert WAV to MP3 using lame
                val process = ProcessBuilder(
                    "lame", "--preset", "standard", 
                    tempWavFile.absolutePath, tempMp3File.absolutePath
                ).start()

                // Check process result
                if (process.waitFor() != 0) {
                    println("Error converting to MP3: ${process.errorStream.bufferedReader().readText()}")
                    pcmData // Return original data if conversion fails
                } else {
                    // Read and return the MP3 file
                    tempMp3File.readBytes()
                }
            } finally {
                // Clean up temporary files
                tempWavFile.delete()
                tempMp3File.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            pcmData // Return original data if conversion fails
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            value.toByte(),
            (value.toInt() shr 8).toByte()
        )
    }

}

/**
 * Audio player class that handles playing audio using JLayer MP3 library
 */
class AudioPlayer {
    private var player: Player? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playerJob: Job? = null

    fun cleanup() {
        stop()
        coroutineScope.cancel()
    }

    fun playAudio(audioData: ByteArray, onComplete: () -> Unit) {
        try {
            // Stop any existing playback
            stop()

            // Create a new player with the audio data
            val inputStream = ByteArrayInputStream(audioData)
            player = Player(inputStream)

            // Play in a coroutine
            playerJob = coroutineScope.launch {
                try {
                    player?.play()
                    // When playback is complete, call onComplete on the main thread
                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onComplete()
        }
    }

    fun stop() {
        player?.close()
        playerJob?.cancel()
        player = null
        playerJob = null
    }
}

/**
 * Composable function for the audio chat screen
 */
@Composable
fun AudioChatScreen(httpClient: HttpClient, conversationId: String) {
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    // Animation for the record button
    val animatedSize by animateFloatAsState(
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

    // Animation for the agent icon
    val agentScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "agentScale"
    )

    // Create audio recorder and player
    val audioRecorder = remember(conversationId) { AudioRecorder() }
    val audioPlayer = remember { AudioPlayer() }

    // Cleanup recorder when the instance changes or is disposed
    DisposableEffect(audioRecorder) {
        onDispose {
            audioRecorder.cleanup()
        }
    }
    // Cleanup player when the composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.cleanup()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Agent icon (only visible when playing)
            if (isPlaying) {
                Image(
                    painter = painterResource("AgentIcon.png"),
                    contentDescription = "Agent",
                    modifier = Modifier
                        .size(120.dp)
                        .scale(agentScale),
                    alignment = Alignment.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Record button
            Box(
                modifier = Modifier
                    .size(120.dp * animatedSize)
                    .clip(CircleShape)
                    .background(if (isRecording) Color(0xC7900DD7) else Color.LightGray)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (!isProcessing) {
                            if (isRecording) {
                                // Stop recording and send audio
                                isRecording = false
                                isProcessing = true

                                scope.launch {
                                    try {
                                        val response = uploadRecording(
                                            audioRecorder = audioRecorder,
                                            httpClient = httpClient,
                                            url = "http://localhost:8082/audio-chat",
                                            conversationId = conversationId
                                        )

                                        if (response.status.value != 200) {
                                            throw Exception("Server returned error: ${response.status.value} ${response.status.description}")
                                        }

                                        val responseAudio = response.body<ByteArray>()

                                        isPlaying = true
                                        audioPlayer.playAudio(responseAudio) {
                                            isPlaying = false
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            } else {
                                // Start recording
                                isRecording = true
                                audioRecorder.startRecording()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecording) "Stop" else "Record",
                    color = Color.White
                )
            }

            // Status text
            Text(
                text = when {
                    isRecording -> "Recording... (Release to send)"
                    isProcessing -> "Processing..."
                    isPlaying -> "Playing response..."
                    else -> "Tap and hold to record"
                },
                style = MaterialTheme.typography.bodyLarge
            )

            // Progress indicator for processing
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
