package dev.example.edd

import dev.example.CapturedToolCall
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EddEvalSupportTest {

    @Test
    fun `asToolCalls maps a recorded call to a typed ToolCall with parsed arguments`() {
        val recorded = listOf(
            CapturedToolCall(
                toolName = "conference-session-search",
                inputJson = """{"query":"kotlin coroutines"}""",
                output = """[{"title":"Coroutines Deep Dive"}]"""
            )
        )

        val calls = recorded.asToolCalls()

        calls shouldHaveSize 1
        calls.first().name() shouldBe "conference-session-search"
        calls.first().arguments() shouldBe mapOf("query" to "kotlin coroutines")
        calls.first().result() shouldBe """[{"title":"Coroutines Deep Dive"}]"""
    }

    @Test
    fun `asToolCalls yields empty arguments when the recorded input is not valid json`() {
        val recorded = listOf(
            CapturedToolCall(toolName = "general-venue-information-kotlinconf", inputJson = "", output = "Messegelaende")
        )

        val calls = recorded.asToolCalls()

        calls shouldHaveSize 1
        calls.first().arguments() shouldBe emptyMap()
        calls.first().result() shouldBe "Messegelaende"
    }

    @Test
    fun `expectedToolCalls produces name-keyed rows for the tool matchers`() {
        expectedToolCalls("add-preferred-sessions", "conference-session-search") shouldBe listOf(
            mapOf("name" to "add-preferred-sessions"),
            mapOf("name" to "conference-session-search")
        )
    }
}
