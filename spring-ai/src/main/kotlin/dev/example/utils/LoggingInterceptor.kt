package dev.example.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.example.utils.RestClientInterceptor.Companion.toJsonOrRaw
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.lang.Nullable
import org.springframework.util.Assert
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Options for configuring the HTTP clients used by the models.
 */
@JvmRecord
data class HttpClientConfig(
    val connectTimeout: Duration,
    val readTimeout: Duration,
    @field:Nullable @param:Nullable val sslBundle: String?,
    val logRequests: Boolean,
    val logResponses: Boolean,
) {
    class Builder internal constructor() {
        private var connectTimeout: Duration = Duration.ofSeconds(10)
        private var readTimeout: Duration = Duration.ofSeconds(60)

        @Nullable
        private var sslBundle: String? = null
        private var logRequests = false
        private var logResponses = false

        fun connectTimeout(connectTimeout: Duration): Builder {
            this.connectTimeout = connectTimeout
            return this
        }

        fun readTimeout(readTimeout: Duration): Builder {
            this.readTimeout = readTimeout
            return this
        }

        fun sslBundle(sslBundle: String?): Builder {
            this.sslBundle = sslBundle
            return this
        }

        fun logRequests(logRequests: Boolean): Builder {
            this.logRequests = logRequests
            return this
        }

        fun logResponses(logResponses: Boolean): Builder {
            this.logResponses = logResponses
            return this
        }

        fun build(): HttpClientConfig {
            return HttpClientConfig(connectTimeout, readTimeout, sslBundle, logRequests, logResponses)
        }
    }

    init {
        Assert.notNull(connectTimeout, "connectTimeout must not be null")
        Assert.notNull(readTimeout, "readTimeout must not be null")
    }

    companion object {
        fun builder(): Builder {
            return Builder()
        }
    }
}

class RestClientInterceptor : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        logRequest(request, body)
        val response = execution.execute(request, body)
        val responseWrapper = BufferingClientHttpResponseWrapper(response)
        logResponse(responseWrapper)
        return responseWrapper
    }

    fun logRequest(request: HttpRequest, bytes: ByteArray) {

        val request = "Request:\n\tRequest: ${request.method} ${request.uri}\n" +
        "\tHeaders: ${request.headers}\n" +
        "\tBody: ${bytes.toJsonOrRaw()}"
        logger.info(request)
    }

    fun logResponse(response: ClientHttpResponse) {
        val response = "Response:\n\tResponse: ${response.statusCode} ${response.statusText}\n" +
        "\tHeaders: ${response.headers}\n" +
        "\tBody: ${response.body.reader().readText()}"
        logger.info(response)
    }

    // A Groovy-ier version of:
    // https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/http/client/BufferingClientHttpResponseWrapper.java
    class BufferingClientHttpResponseWrapper(val response: ClientHttpResponse) : ClientHttpResponse by response {

        var body: ByteArray? = null

        override fun getBody(): InputStream {
            if (this.body == null) {
                this.body = response.body.readAllBytes()
            }
            return ByteArrayInputStream(this.body)
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(RestClientInterceptor::class.java)
        val mapper = jacksonObjectMapper()
        fun ByteArray.toJsonOrRaw(): String = try {
            mapper.readTree(this).toPrettyString()
        } catch (e: Exception) {
            String(this)
        }

    }


}

/**
 * OkHttp counterpart of [RestClientInterceptor]. Spring AI 2.x talks to OpenAI through the OpenAI Java SDK on OkHttp
 * instead of Spring's RestClient, so a `ClientHttpRequestInterceptor` never sees those calls.
 * Register it via an `OpenAiHttpClientBuilderCustomizer` bean (see `AiConfig`).
 */
class OkHttpLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        logRequest(request)
        val response = chain.proceed(request)
        logResponse(response)
        return response
    }

    fun logRequest(request: Request) {
        val request = "Request:\n\tRequest: ${request.method} ${request.url}\n" +
        "\tHeaders: ${request.headers.redacted()}\n" +
        "\tBody: ${request.body.loggable()}"
        logger.info(request)
    }

    fun logResponse(response: Response) {
        val response = "Response:\n\tResponse: ${response.code} ${response.message}\n" +
        "\tHeaders: ${response.headers}\n" +
        "\tBody: ${response.loggableBody()}"
        logger.info(response)
    }

    companion object {
        val logger = LoggerFactory.getLogger(OkHttpLoggingInterceptor::class.java)

        private fun Headers.redacted(): String =
            joinToString(", ", "[", "]") { (name, value) ->
                if (name.equals("Authorization", ignoreCase = true)) "$name:\"Bearer ***\"" else "$name:\"$value\""
            }

        // One-shot bodies (e.g. streamed uploads) can only be written once, so they are not read for logging.
        private fun RequestBody?.loggable(): String = when {
            this == null -> ""
            isOneShot() -> "<one-shot body, ${contentLength()} bytes>"
            !contentType().isText() -> "<${contentType()}, ${contentLength()} bytes>"
            else -> Buffer().also { writeTo(it) }.readByteArray().toJsonOrRaw()
        }

        // peekBody leaves the original body readable for the SDK; SSE streams are skipped since peeking would block.
        private fun Response.loggableBody(): String {
            val type = body?.contentType()
            return when {
                type?.subtype == "event-stream" -> "<event stream>"
                !type.isText() -> "<$type, ${body?.contentLength()} bytes>"
                else -> peekBody(Long.MAX_VALUE).bytes().toJsonOrRaw()
            }
        }

        private fun MediaType?.isText(): Boolean =
            this == null || type == "text" || subtype.contains("json") || subtype.contains("x-www-form-urlencoded")
    }
}

