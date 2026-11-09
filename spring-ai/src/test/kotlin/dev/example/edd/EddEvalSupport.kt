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
import org.springframework.ai.openai.OpenAiChatOptions
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
 * The seed data bakes the offset at UTC+1 (CET; J-Fall is in November), so an eval that pins a
 * "current time" must state which clock it means. Writing the prompt's local time with a `Z` suffix
 * silently shifts it by one hour and makes time-based evaluators judge the agent against a clock it never saw.
 */
val CONFERENCE_ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

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

/**
 * The judge model: stronger than the app's model (application.properties) and pinned to a snapshot,
 * so an alias update cannot drift recorded scores. GPT-5.x only accepts temperature 0 with
 * reasoning switched off.
 */
private fun judgeOptions(): OpenAiChatOptions.Builder = OpenAiChatOptions.builder()
    .model("gpt-5.5-2026-04-23")
    .reasoningEffort("none")
    .temperature(0.0)

/** Shared LLM-judge configuration used by the eval classes. Clones the builder so the app's client keeps its own model. */
fun springAiJudge(builder: ChatClient.Builder): JudgeLM =
    SpringAiSupport.asJudge(builder.clone().defaultOptions(judgeOptions()))

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

/**
 * JUnit tags that say how early an eval may stop someone. An eval climbs by earning more checks,
 * sooner; nothing is ever dropped on the way up. The stage is earned from the run history on the
 * Dokimos server (see `tools/EvalStabilityReport`), never assigned by taste.
 *
 *  - [WATCH]: `.print()` and the dashboard only. Nothing blocks. Every new eval starts here, and known-red
 *    evals stay here until the agent is fixed.
 *  - [GUARD]: every pipeline run: `runs = evalRuns()` and `assertNoRegression()` against the committed
 *    baseline, which is fair only on N-run means. Can stop the pipeline on drift.
 *  - [BLOCK]: every pipeline run, `.assert()` on top of `assertNoRegression()`: any red item in any run fails.
 *    Earned after >= 30 consecutive fully green runs while passing GUARD, which is what shows the eval meets
 *    the bar every time. Nothing is dropped on the way up.
 *
 * Select stages with `./mvnw test -Dgroups=block`, `-Dgroups=guard,block`, or run everything (watch evals
 * report, guard and block evals assert) with `EVAL_RUNS` set for the pipeline.
 */
object EvalTier {
    const val WATCH = "watch"
    const val GUARD = "guard"
    const val BLOCK = "block"
}

/**
 * How often each example runs in this job: `EVAL_RUNS` (nightly sets e.g. 10), default 1. The regression
 * gate averages per item across runs, so N turns one noisy run into a mean the baseline can be compared with.
 */
fun evalRuns(): Int = System.getenv("EVAL_RUNS")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
