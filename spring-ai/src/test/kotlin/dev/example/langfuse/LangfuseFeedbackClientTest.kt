package dev.example.langfuse

import com.langfuse.client.LangfuseClient
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant

/** Runs the real Langfuse Java client against a stub of `GET /api/public/v2/observations`. */
class LangfuseFeedbackClientTest {

    private val requests = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            val query = URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), Charsets.UTF_8)
            requests += "${exchange.requestURI.path}?$query"
            val body = when {
                "traceId=trace-a" in query -> TRACE_PAGE
                "cursor=page-2" in query -> FEEDBACK_PAGE_2
                else -> FEEDBACK_PAGE_1
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val client = LangfuseFeedbackClient(
        LangfuseClient.builder().url("http://localhost:${server.address.port}").credentials("pk", "sk").build()
    )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `reads feedback from the v2 observations api across pages, in the v4 and both legacy shapes`() {
        val feedback = client.fetchFeedback(limit = 10)

        feedback shouldHaveSize 3
        assertSoftly {
            requests shouldHaveSize 2
            requests.forEach {
                it shouldContain "/api/public/v2/observations?"
                it shouldContain "name=feedback"
                it shouldContain "fromStartTime="
                it shouldContain "toStartTime="
                it shouldNotContain "parseIoAsJson"
            }
            requests[1] shouldContain "cursor=page-2"

            with(feedback[0]) {
                traceId shouldBe "trace-a"
                sessionId shouldBe "session-a"
                request shouldBe "Where is the venue?"
                answer shouldBe "In Antwerp."
                rating shouldBe 0.0
                reason shouldBe "wrong city"
                timestamp shouldBe Instant.parse("2026-10-02T18:00:00Z")
            }
            with(feedback[1]) {
                sessionId shouldBe "session-legacy"
                request shouldBe "legacy request"
                answer shouldBe "legacy answer"
                rating shouldBe 1.0
                reason shouldBe null
            }
            with(feedback[2]) {
                sessionId shouldBe "session-flat"
                request shouldBe "flat request"
                rating shouldBe 0.0
            }
        }
    }

    @Test
    fun `reconstructs a trace from its observations with the root carrying the overall io`() {
        val trace = client.fetchTrace("trace-a", Instant.parse("2026-10-02T18:00:00Z"))

        assertSoftly {
            requests.single() shouldContain "traceId=trace-a"
            requests.single() shouldContain "fromStartTime=2026-10-01T18:00:00Z"
            trace.observations shouldHaveSize 2
            trace.root?.get("input") shouldBe "Where is the venue?"
            trace.totalCost shouldBe 0.00172
            trace.htmlPath shouldBe "/project/project-1/traces/trace-a"
        }
    }

    companion object {
        private val FEEDBACK_PAGE_1 = """
            {"data": [{"id": "obs-1", "traceId": "trace-a", "startTime": "2026-10-02T18:00:00.000Z", "type": "SPAN",
              "name": "feedback", "sessionId": "session-a", "input": "Where is the venue?", "output": "In Antwerp.",
              "metadata": {"feedback_rating": "DOWN", "feedback_reason": "wrong city"}}],
             "meta": {"cursor": "page-2"}}
        """.trimIndent()
        private val FEEDBACK_PAGE_2 = """
            {"data": [{"id": "obs-2", "traceId": "trace-b", "startTime": "2026-09-01T10:00:00.000Z", "type": "SPAN",
              "name": "feedback", "sessionId": "", "input": null, "output": null,
              "metadata": {"attributes": {"langfuse.session.id": "session-legacy", "langfuse.user.request": "legacy request",
                "langfuse.answer": "legacy answer", "langfuse.feedback": "UP", "langfuse.feedback.reason": ""}}},
             {"id": "obs-3", "traceId": "trace-c", "startTime": "2026-09-01T09:00:00.000Z", "type": "SPAN",
              "name": "feedback", "sessionId": "session-flat", "input": null, "output": null,
              "metadata": {"attributes.langfuse.user.request": "flat request", "attributes.langfuse.feedback": "DOWN"}}],
             "meta": {}}
        """.trimIndent()
        private val TRACE_PAGE = """
            {"data": [
              {"id": "gen-1", "traceId": "trace-a", "projectId": "project-1", "parentObservationId": "root-1",
               "startTime": "2026-10-02T17:59:59.500Z", "type": "GENERATION", "name": "chat gpt-4.1", "totalCost": 0.00172},
              {"id": "root-1", "traceId": "trace-a", "projectId": "project-1", "parentObservationId": null,
               "startTime": "2026-10-02T17:59:59.000Z", "type": "SPAN", "name": "http post /chat",
               "input": "Where is the venue?", "output": "In Antwerp."}],
             "meta": {}}
        """.trimIndent()
    }
}
