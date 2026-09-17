package dev.example.edd

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dokimos.core.ExperimentResult
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.agents.ToolCall
import dev.dokimos.core.agents.ToolDefinition
import dev.dokimos.server.client.DokimosServerReporter
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.CapturedToolCall
import io.kotest.assertions.AssertionErrorBuilder.Companion.fail
import org.springframework.ai.chat.client.ChatClient
import java.time.LocalDateTime
import java.time.ZoneId

private val toolArgsMapper = ObjectMapper()

/**
 * Adapts recorded tool calls into Dokimos [ToolCall]s — the shape the built-in agent evaluators
 * (toolCorrectness / toolTrajectory / toolArgumentHallucination / toolEfficiency) expect under the
 * "toolCalls" output key. The recorded `inputJson` is parsed into the arguments map so
 * argument-level evaluators can inspect it.
 *
 * Spring AI executes tools inside the ChatClient and `AIController.chat` returns a bare String, so
 * `SpringAiSupport.toToolCalls` — which needs an AssistantMessage — cannot be used here.
 */
fun List<CapturedToolCall>.asToolCalls(): List<ToolCall> = map { call ->
    @Suppress("UNCHECKED_CAST")
    val arguments: Map<String, Any> = runCatching {
        toolArgsMapper.readValue(call.inputJson, Map::class.java) as Map<String, Any>
    }.getOrDefault(emptyMap())
    ToolCall.builder().name(call.toolName).arguments(arguments).result(call.output).build()
}

/**
 * The zone the session corpus is expressed in.
 *
 * Two clocks coexist in this app and they are easy to confuse:
 *  - `ConferenceSession.startsAt` (and the seeded `talks.sql` `startsAt`) is a true UTC instant;
 *  - `startsAtLocalDateTime`, which is what the agent actually sees via `searchSessions`, is that
 *    instant rendered as conference-local wall-clock time, with no offset suffix.
 *
 * The seed data bakes the offset at UTC+2, so an eval that pins a "current time" must state which
 * clock it means. Writing the prompt's local time with a `Z` suffix silently shifts it by two hours
 * and makes time-based evaluators judge the agent against a clock it never saw.
 */
val CONFERENCE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Converts a conference-local wall-clock time — the same one the eval prompt states in prose — into
 * the UTC instant that evaluators compare `startsAt` against.
 *
 * Use this instead of hand-writing an instant, so the prompt and the metadata cannot drift apart.
 */
fun conferenceLocalTime(localDateTime: String): String =
    LocalDateTime.parse(localDateTime).atZone(CONFERENCE_ZONE).toInstant().toString()

/** Expected tool calls for the name-based matchers — only the names matter. */
fun expectedToolCalls(vararg names: String): List<Map<String, Any>> = names.map { mapOf("name" to it) }

/** Shared LLM-judge configuration used by the eval classes. */
fun springAiJudge(builder: ChatClient.Builder): JudgeLM = SpringAiSupport.asJudge(builder)

/** Reports eval results to the local Dokimos dashboard. */
fun dokimosReporter(): DokimosServerReporter = DokimosServerReporter.builder()
    .serverUrl("http://localhost:8080")
    .projectName("kotlinconf-chat-app-evals")
    .build()

/**
 * The tools the agent was given, in Dokimos form. Needed by the evaluators that check calls against
 * the available tools (toolCallValidity, toolNameReliability, toolDescriptionReliability).
 */
fun conferenceToolDefinitions(controller: AIController): List<ToolDefinition> =
    SpringAiSupport.toToolDefinitions(controller.interceptedTools.map { it.toolDefinition })

/** Fails the test if any run produced a failing item result. */
fun ExperimentResult.assert():ExperimentResult = apply {
    runResults.filter { it.failCount() > 0 }.takeIf { it.isNotEmpty() }?.let {
        fail(it.joinToString { it.itemResults().joinToString("\n") })
    }
}

fun ExperimentResult.print()  = apply{   // 6. Display results
    println("=".repeat(60))
    println("Evaluation Results")
    println("=".repeat(60))
    println("Pass rate: ${"%.0f".format(this.passRate() * 100)}%")
    println()

    println("Average Scores:")
    evaluatorNames().forEach { evalutor ->
        println("  $evalutor: ${"%.2f".format(this.averageScore(evalutor))}")
    }
    println()

    println("Detailed Results:")
    println("-".repeat(60))
    this.itemResults().forEach { item ->
        println()
        println("Question: ${item.example().input()}")
        println("Response: ${item.actualOutputs()["output"]}")
        println("Expected: ${item.example().expectedOutput()}")
        println("Status: ${if (item.success()) "✅ PASS" else "❌ FAIL"}")
        println("Scores:")
        item.evalResults().forEach { eval ->
            println("  • ${eval.name()}: ${"%.2f".format(eval.score())}${if (eval.success()) " ✅" else " ❌"}")
            // Only failures explain themselves — a passing run stays readable on stage.
            if (!eval.success()) {
                eval.reason()?.lineSequence()?.forEach { println("      $it") }
            }
        }
        println("- - ".repeat(20))
    }
    println()
    println("=".repeat(60))
}
