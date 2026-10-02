package dev.example

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
import org.springframework.ai.chat.memory.ChatMemory
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository
import org.springframework.ai.chat.memory.MessageWindowChatMemory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.image.ImageModel
import org.springframework.ai.image.ImageOptions
import org.springframework.ai.image.ImageOptionsBuilder
import org.springframework.ai.openai.*
import org.springframework.ai.openai.OpenAiAudioSpeechOptions.AudioResponseFormat
import org.springframework.ai.openai.OpenAiAudioSpeechOptions.Voice
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import dev.example.utils.OkHttpLoggingInterceptor
import com.openai.models.audio.AudioResponseFormat as TranscriptResponseFormat
import org.springframework.ai.tool.execution.ToolExecutionException
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import com.langfuse.client.LangfuseClient
import org.springframework.beans.factory.annotation.Value


@Configuration
class AiConfig {
    @Bean
    fun chatClient(
        // The auto-configured builder carries the ObservationRegistry. ChatClient.builder(chatModel) would use a
        // no-op registry, and since Spring AI 2.0 the ChatClient executes tools itself, so tool calls go untraced.
        chatClientBuilder: ChatClient.Builder, chatMemory: ChatMemory,
    ): ChatClient {
        val builder = chatClientBuilder
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
            )
        return builder.build()
    }

    /** Logs every OpenAI request/response. Auto-configured models (chat, embedding) pick this bean up themselves. */
    @Bean
    fun openAiRequestLogging() = OpenAiHttpClientBuilderCustomizer { it.interceptor(OkHttpLoggingInterceptor()) }

    @Bean
    fun transcriptionOptions(@Value("#{environment.OPENAI_API_KEY}") key: String): OpenAiAudioTranscriptionOptions {
        return OpenAiAudioTranscriptionOptions.builder()
            .apiKey(key)
            .language("en")
            .prompt("Create transcription for this audio file.")
            .temperature(0f)
            //.responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)
            .model("whisper-1")
            .responseFormat(TranscriptResponseFormat.JSON)
            .build()
    }


    @Bean
    fun transcriptionModel(
        transcriptionOptions: OpenAiAudioTranscriptionOptions,
        openAiRequestLogging: OpenAiHttpClientBuilderCustomizer,
    ): OpenAiAudioTranscriptionModel =
        OpenAiAudioTranscriptionModel.builder().options(transcriptionOptions)
            .httpClientBuilderCustomizer(openAiRequestLogging).build()


    @Bean
    fun speachOptions(@Value("#{environment.OPENAI_API_KEY}") key: String): OpenAiAudioSpeechOptions = OpenAiAudioSpeechOptions.builder()
        .apiKey(key)
        .model("tts-1")
        .responseFormat(AudioResponseFormat.MP3)
        .voice(Voice.ALLOY)
        .speed(1.0)
        .build()

    @Bean
    fun speechModel(
        speechOptions: OpenAiAudioSpeechOptions,
        openAiRequestLogging: OpenAiHttpClientBuilderCustomizer,
    ): OpenAiAudioSpeechModel =
        OpenAiAudioSpeechModel.builder().options(speechOptions)
            .httpClientBuilderCustomizer(openAiRequestLogging).build()

    @Bean
    fun imageOptions(): ImageOptions = ImageOptionsBuilder.builder()
        .model("dall-e-3")
        .height(1024)
        .width(1024)
        .build()

    @Bean
    fun imageModel(
        @Value("#{environment.OPENAI_API_KEY}") apiKey: String,
        openAiRequestLogging: OpenAiHttpClientBuilderCustomizer,
    ): ImageModel = OpenAiImageModel.builder()
        .options(OpenAiImageOptions.builder().apiKey(apiKey).build())
        .httpClientBuilderCustomizer(openAiRequestLogging)
        .build()


    @Bean
    fun chatMemory() = MessageWindowChatMemory.builder().chatMemoryRepository(InMemoryChatMemoryRepository()).build()

    @Bean
    fun webClient() = RestClient.builder().build()

    @Bean
    fun langfuseClient(
        @Value("\${LANGFUSE_BASE_URL:https://cloud.langfuse.com}") baseUrl: String,
        @Value("\${LANGFUSE_PUBLIC_KEY}") publicKey: String,
        @Value("\${LANGFUSE_SECRET_KEY}") secretKey: String,
    ): LangfuseClient {
        require(publicKey.isNotBlank()) { "LANGFUSE_PUBLIC_KEY must be set" }
        require(secretKey.isNotBlank()) { "LANGFUSE_SECRET_KEY must be set" }
        return LangfuseClient.builder()
            .url(baseUrl)
            .credentials(publicKey, secretKey)
            .build()
    }

    @Bean
    fun toolExecutionExceptionProcessor() = ToolExecutionExceptionProcessor { ex: ToolExecutionException ->
        val cause = ex.cause
        val retriable = cause is java.net.SocketTimeoutException ||
                cause is java.io.IOException
        val msg = (cause?.message ?: ex.message ?: "Unexpected error")
            .replace(Regex("\\s+"), " ")
            .take(200) // avoid leaking stack
        """{"error":{"message":"$msg","retriable":$retriable}}"""
    }


    /**
     * https://dev.to/mcadariu/springai-llama3-and-pgvector-bragging-rights-2n8o
     */
    @Bean
    fun applicationRunner(
        sessionIngestion: SessionIngestion,
        embeddingModel: EmbeddingModel,
        @Value("\${spring.ai.vectorstore.pgvector.table-name}") tableName: String,
    ): ApplicationRunner {
        logger.info("Embedding Model used: $embeddingModel")
        // The configured table decides the chunking strategy (see ChunkingStrategy); unknown tables get WHOLE.
        val strategy = ChunkingStrategy.forTable(tableName) ?: ChunkingStrategy.WHOLE
        return ApplicationRunner { sessionIngestion.ingestIfEmpty(strategy, tableName) }
    }
}

//class ToolConfig(val conferenceTools: ConferenceTools) {
//    @Bean
//    fun conferenceToolsCallback(conferenceTools: ConferenceTools): ToolCallbackProvider {
//        return MethodToolCallbackProvider.builder().toolObjects(conferenceTools).build()
//    }
//
//}


