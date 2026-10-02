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
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.ai.openai.api.OpenAiAudioApi.SpeechRequest.AudioResponseFormat
import org.springframework.ai.openai.api.OpenAiAudioApi.TranscriptResponseFormat
import org.springframework.ai.openai.api.OpenAiImageApi
import org.springframework.ai.retry.RetryUtils
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
        openAiChatModel: OpenAiChatModel, chatMemory: ChatMemory,
    ): ChatClient {
        val builder = ChatClient.builder(openAiChatModel)
            .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
            )
        return builder.build()
    }

    @Bean
    fun openAiAudioApi(@Value("#{environment.OPENAI_API_KEY}") key: String, restClientBuilder: RestClient.Builder) =
        OpenAiAudioApi.Builder().baseUrl("https://api.openai.com").apiKey(key).restClientBuilder(restClientBuilder)
            .responseErrorHandler(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER).build()


    @Bean
    fun transcriptionOptions(): OpenAiAudioTranscriptionOptions {
        return OpenAiAudioTranscriptionOptions.builder()
            .language("en")
            .prompt("Create transcription for this audio file.")
            .temperature(0f)
            //.responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)
            .model("whisper-1")
            .responseFormat(TranscriptResponseFormat.JSON)
            .build()
    }


    @Bean
    fun transcriptionModel(openAiAudioApi: OpenAiAudioApi, transcriptionOptions: OpenAiAudioTranscriptionOptions) =
        OpenAiAudioTranscriptionModel(openAiAudioApi, transcriptionOptions)


    @Bean
    fun speachOptions(): OpenAiAudioSpeechOptions = OpenAiAudioSpeechOptions.builder()
        .model(OpenAiAudioApi.TtsModel.TTS_1.getValue())
        .responseFormat(AudioResponseFormat.MP3)
        .voice(OpenAiAudioApi.SpeechRequest.Voice.ALLOY)
        .speed(1.0)
        .build()

    @Bean
    fun speechModel(openAiAudioApi: OpenAiAudioApi, speechOptions: OpenAiAudioSpeechOptions) =
        OpenAiAudioSpeechModel(openAiAudioApi, speechOptions)

    @Bean
    fun imageOptions(): ImageOptions = ImageOptionsBuilder.builder()
        .model("dall-e-3")
        .height(1024)
        .width(1024)
        .build()

    @Bean
    fun imageModel(@Value("#{environment.OPENAI_API_KEY}") apiKey: String): ImageModel {
        return OpenAiImageModel(OpenAiImageApi.Builder().apiKey(apiKey).build())
    }


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

//@Configuration(proxyBeanMethods = false)
//class ToolConfig(val conferenceTools: ConferenceTools) {
//    @Bean
//    fun conferenceToolsCallback(conferenceTools: ConferenceTools): ToolCallbackProvider {
//        return MethodToolCallbackProvider.builder().toolObjects(conferenceTools).build()
//    }
//
//}


