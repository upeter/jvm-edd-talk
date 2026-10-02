package dev.example

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID
import org.springframework.ai.openai.OpenAiAudioSpeechModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.context.annotation.Lazy
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.*
import kotlin.random.Random.Default.nextInt
import dev.example.observability.LangfuseAttributes.METADATA_FEEDBACK_RATING
import dev.example.observability.LangfuseAttributes.METADATA_FEEDBACK_REASON
import dev.example.observability.langfuseObservation
import io.micrometer.observation.ObservationRegistry

data class ChatMessage(val message: String, val conversationId: String)
data class TranscribedMessageReply(val transcribedInputText: String, val outputText: String)
data class FeedbackRequest(
    val request: String,
    val answer: String,
    val sessionId: String,
    val rating: String,
    val reason: String? = null
)
@RestController
class AIController(
    val openAiAudioSpeechModel: OpenAiAudioSpeechModel,
    val openAiAudioTranscriptionModel: OpenAiAudioTranscriptionModel,
    @Lazy val  chatClient: ChatClient,
    val toolCallbackRecorder: ToolCallRecorder,
    val conferenceTools: ConferenceTools,
    private val observationRegistry: ObservationRegistry
) {


    @PostMapping("/chat_")
    fun chat_(@RequestBody chatMessage: ChatMessage): String? =
        chatClient
            .prompt()
            .system(SYSTEM_PROMPT)
            .user(chatMessage.message)
            .tools(conferenceTools)
            .toolContext(mapOf("conversationId" to chatMessage.conversationId))
            .advisors {
                it.param(CONVERSATION_ID, chatMessage.conversationId)
            }
            .call()
            .content()


















    val interceptedTools =  ToolCallbacks.from(conferenceTools).toList().map { RecordingToolCallback(it, toolCallbackRecorder) }



    @PostMapping("/chat")
    fun chat(@RequestBody chatMessage: ChatMessage): String? =
        observationRegistry.langfuseObservation("chat", chatMessage.conversationId, chatMessage.message) {
            chatClient
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(chatMessage.message)
                //.toolContext(mapOf("progressToken" to "token-${nextInt()}"))
                .toolContext(mapOf("conversationId" to  chatMessage.conversationId))
                //.tools(conferenceTools)
                .tools(interceptedTools)
                .advisors {
                    it.param(CONVERSATION_ID, chatMessage.conversationId)
                }
                .call()
                .content()
        }











    @PostMapping("/feedback")
    fun feedback(@RequestBody feedbackRequest: FeedbackRequest): ResponseEntity<String> {
        // The feedback observation records the rated exchange: request as input, answer as output.
        observationRegistry.langfuseObservation(
            name = "feedback",
            sessionId = feedbackRequest.sessionId,
            input = feedbackRequest.request,
            metadata = mapOf(
                METADATA_FEEDBACK_RATING to feedbackRequest.rating,
                METADATA_FEEDBACK_REASON to feedbackRequest.reason.orEmpty(),
            ),
        ) { feedbackRequest.answer }
        return ResponseEntity.ok("ok")
    }











    @PostMapping("/audio-in-text-out-chat", consumes = ["multipart/form-data"])
    @ResponseBody
    fun audioInTextOutChat(@RequestParam("audio") audioFile: MultipartFile, @RequestParam("conversationId", required = false) conversationId: String? = null):TranscribedMessageReply {
        // 1. Transcribe audio to text
        val transcriptionPrompt = AudioTranscriptionPrompt(object:InputStreamResource(audioFile.inputStream, "audio") {
            override fun getFilename(): String = UUID.randomUUID().toString().replace("-", "") + ".mp3"
        })
        val transcriptionResponse = openAiAudioTranscriptionModel.call(transcriptionPrompt)
        val transcribedText = transcriptionResponse.result.output

        // 2. Call the chat method with the transcribed text
        val chatMessage = ChatMessage(transcribedText, conversationId ?: UUID.randomUUID().toString())
        return chat(chatMessage).let{TranscribedMessageReply(transcribedText, it?:"I couldn't understand that. Please try again.")}
    }


            @PostMapping("/audio-chat", consumes = ["multipart/form-data"], produces = ["application/octet-stream"])
    fun audioChat(@RequestParam("audio") audioFile: MultipartFile, @RequestParam("conversationId", required = false) conversationId: String? = null): ByteArray {
        // 1. Transcribe audio to text
        val transcriptionPrompt = AudioTranscriptionPrompt(object:InputStreamResource(audioFile.inputStream, "audio"){
            override fun getFilename(): String = UUID.randomUUID().toString().replace("-", "") + ".mp3"
        })

        val transcriptionResponse = openAiAudioTranscriptionModel.call(transcriptionPrompt)
        val transcribedText = transcriptionResponse.result.output

        // 2. Call the chat method with the transcribed text
        val chatMessage = ChatMessage(transcribedText, conversationId ?: UUID.randomUUID().toString())

        val chatResponse = observationRegistry.langfuseObservation("audio-chat", chatMessage.conversationId, chatMessage.message) {
            chatClient
                .prompt()
                .system(SYSTEM_PROMPT_AUDIO)
                .user(chatMessage.message)
               // .tools(conferenceTools)
                .toolCallbacks(interceptedTools)
                .toolContext(mapOf("conversationId" to chatMessage.conversationId))
                .advisors {
                    it.param(CONVERSATION_ID, chatMessage.conversationId)
                }
                .call()
                .content()
        }

        // 3. Convert the response to audio
        return openAiAudioSpeechModel.call(chatResponse ?: "I couldn't understand that. Please try again.")
    }

    companion object {
            val SYSTEM_PROMPT = """
            You are a helper assistant for the Devoxx Belgium 2026 conference. 
            Style: 
            - Respond in a friendly, helpful manner.
            Objective: Assist the user in finding the best matching sessions for his preferences and provide relevant information about the conference.
            Make use of tools to fetch relevant information about sessions, speakers, and venue details.
            """














        
        val SYSTEM_PROMPT_AUDIO = """
            You are a helper assistant for the Devoxx Belgium 2026 conference. 
            Respond in a friendly, helpful manner, yet crisp manner.
            Objective: Assist the user in finding the best matching sessions for his preferences and provide relevant information about the conference.
            Make use of tools to fetch relevant information about sessions, preferred sessions, venue details and speakers. Also include web searches.
            
            Only provide session information if the user requests it. If so:
             - Never list more than 3 sessions in the response. 
             - Only name the information in the provided data, do not add your own summary.
            """
    }
}
