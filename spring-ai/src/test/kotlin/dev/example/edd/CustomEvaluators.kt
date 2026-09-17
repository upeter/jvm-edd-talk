package dev.example.edd

import dev.dokimos.core.BaseEvaluator
import dev.dokimos.core.EvalResult
import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.agents.ToolCall
import dev.dokimos.kotlin.core.EvalResult
import dev.dokimos.kotlin.dsl.DokimosDsl
import dev.dokimos.kotlin.dsl.evaluators.EvaluatorsDsl
import dev.example.ConferenceSession
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ResponseLengthEvaluator(
    private val minLength: Int,
    private val maxLength: Int,
    private val evaluatorName: String = "Length Check"
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val output = testCase.actualOutput()
        val length = output.length

        val withinBounds = length in minLength..maxLength
        val score = if (withinBounds) 1.0 else 0.0
        val success = score >= threshold()
        val reason = "Output length $length (expected $minLength-$maxLength)"

        return EvalResult(
            name(),
            score,
            threshold(),
            success,
            reason,
            mutableMapOf()
        )
    }
}

/**
 * Asserts that one specific named tool was called, tolerant of whatever other tools the agent also
 * called. Optionally also checks that the tool's result contained an expected value.
 *
 * This fills a gap the built-in agent evaluators leave open:
 *  - `toolCorrectness` set-matches the *entire* call list, so it penalizes any extra legitimate call;
 *  - `toolTrajectory` matches a full golden name+argument sequence, which an LLM's nondeterministic
 *    arguments rarely reproduce exactly.
 *
 * Prefer a built-in when the exact set or order of calls can be pinned down; reach for this when all
 * you need is "tool X was used somewhere along the way".
 *
 * Reads the recorded calls from the "toolCalls" output key, as produced by [asToolCalls].
 */
class ToolPresenceEvaluator(
    evaluatorName: String,
    private val expectedToolName: String,
    /** If set, looks up an expected substring in [EvalTestCase.expectedOutputs] and checks the tool result contains it. */
    private val toolOutputKey: String? = null,
) : BaseEvaluator(evaluatorName, 1.0, emptyList()) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val calls = (testCase.actualOutputs()[PARAM_TOOL_CALLS] as? List<*>).orEmpty()
            .mapNotNull { it.asView() }

        val match = calls.firstOrNull { it.name == expectedToolName }
        val expectedOutput = toolOutputKey?.let { testCase.expectedOutputs()[it]?.toString() }
        val outputOk = expectedOutput?.let { match?.result?.contains(it) == true }

        val score = when {
            match == null -> 0.0
            outputOk == false -> 0.0
            else -> 1.0
        }
        val reason = buildString {
            append(if (match != null) "Tool '$expectedToolName' was called" else "Tool '$expectedToolName' was NOT called")
            append(" (calls: ${calls.joinToString { it.name }.ifEmpty { "none" }})")
            if (expectedOutput != null) append("; expected output '$expectedOutput' present=$outputOk")
        }
        return EvalResult(
            name(), score, threshold(), score >= threshold(), reason,
            mapOf("expectedToolName" to expectedToolName, "calledTools" to calls.map { it.name }),
        )
    }

    private data class View(val name: String, val result: String?)

    private fun Any?.asView(): View? = when (this) {
        is ToolCall -> View(name(), result())
        is Map<*, *> -> (this["name"] ?: this["toolName"])?.toString()
            ?.let { View(it, (this["result"] ?: this["toolOutput"])?.toString()) }
        else -> null
    }

    companion object {
        const val PARAM_TOOL_CALLS = "toolCalls"
    }
}

@DokimosDsl
class ToolPresenceEvaluatorDsl {
    var name: String = "Tool Presence"
    var expectedToolName: String? = null
    var toolOutputKey: String? = null

    fun build(): ToolPresenceEvaluator = ToolPresenceEvaluator(
        evaluatorName = name,
        expectedToolName = requireNotNull(expectedToolName) { "expectedToolName must be provided" },
        toolOutputKey = toolOutputKey
    )
}

/** Convenience builder for creating a [ToolPresenceEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.toolPresence(block: ToolPresenceEvaluatorDsl.() -> Unit) {
    evaluator(ToolPresenceEvaluatorDsl().apply(block).build())
}

class ContainsEvaluator(
    evaluatorName: String = "Contains",
    private val containsTextKey: String? = null,
    private val caseSensitive: Boolean = false,
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val output = testCase.actualOutput()
        val containsText = containsTextKey?.let{testCase.expectedOutputs()[it]?.toString() } ?: testCase.expectedOutput()
        val contains = if (caseSensitive) output.contains(containsText) else output.lowercase().contains(containsText.lowercase())

        val score = if (contains) 1.0 else 0.0
        val reason = "Output text $containsText expected in $output"

        return EvalResult(
            name = name(),
            score = score,
            threshold = threshold(),
            reason = reason,
        )
    }
}


@DokimosDsl
class ContainsEvaluatorDsl {
    var name: String = "ContainsEvaluator"
    var containsTextKey: String? = null
    var caseSensitive: Boolean = false

    fun build(): ContainsEvaluator {
        return ContainsEvaluator(
            evaluatorName = name,
            containsTextKey = containsTextKey,
            caseSensitive = caseSensitive
        )
    }
}

/** Convenience builder for creating a [ContainsEvaluator] with a Kotlin DSL block. */
fun EvaluatorsDsl.contains(block: ContainsEvaluatorDsl.() -> Unit) {
    evaluator(ContainsEvaluatorDsl().apply(block).build())
}

class StartedSessionOverlapEvaluator(
    evaluatorName: String = "Started Session Overlap",
    private val preferredSessionsKey: String = "preferredSessions",
    private val currentTimeKey: String = "currentTime",
    private val zone: ZoneId = CONFERENCE_ZONE,
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

    override fun runEvaluation(testCase: EvalTestCase): EvalResult {
        val outputs = testCase.actualOutputs()

        val currentTimeRaw = outputs[currentTimeKey]?.toString()
            ?: return diagnostic(
                score = 0.0,
                headline = "Cannot evaluate — the task returned no '$currentTimeKey' output key.",
                details = listOf(
                    "Add the evaluation clock to the map the task returns, e.g. " +
                        "\"$currentTimeKey\" to example.metadata().getValue(\"$currentTimeKey\").",
                    "Keys the task did return: ${outputs.keys.describe()}.",
                ),
                metadata = mapOf("availableOutputKeys" to outputs.keys.toList()),
            )

        val currentTime = runCatching { Instant.parse(currentTimeRaw) }.getOrElse { failure ->
            return diagnostic(
                score = 0.0,
                headline = "Cannot evaluate — '$currentTimeKey' is not an ISO-8601 instant.",
                details = listOf(
                    "Value was \"$currentTimeRaw\" (${failure.message}).",
                    "Expected a UTC instant such as 2026-05-22T11:30:00Z. A conference-local " +
                        "wall-clock time has to be converted first — see conferenceLocalTime().",
                ),
                metadata = mapOf("currentTimeRaw" to currentTimeRaw),
            )
        }
        val nowLabel = "$currentTime (${currentTime.asLocal()} local, ${zone.id})"

        val rawEntries = outputs[preferredSessionsKey]
        if (rawEntries !is Iterable<*>) {
            return diagnostic(
                score = 0.0,
                headline = "Cannot evaluate — '$preferredSessionsKey' is " +
                    if (rawEntries == null) "missing from the task output." else "not a collection.",
                details = listOfNotNull(
                    rawEntries?.let { "Got ${it::class.simpleName} instead: ${it.toString().take(120)}." },
                    "The task must return the sessions that ended up on the schedule, e.g. " +
                        "\"$preferredSessionsKey\" to sessionPreferenceRepository.getPreferredSessionsBy(conversationId).",
                    "Keys the task did return: ${outputs.keys.describe()}.",
                ),
                metadata = mapOf("availableOutputKeys" to outputs.keys.toList()),
            )
        }

        val entries = rawEntries.toList()
        val unreadable = entries.filter { it.asSessionForOverlap() == null }
        val parsed = entries.mapNotNull { it.asSessionForOverlap() }
            .map { session -> session to runCatching { Instant.parse(session.startsAt) }.getOrNull() }
        val undated = parsed.filter { it.second == null }.map { it.first }
        val dated = parsed.mapNotNull { (session, startsAt) -> startsAt?.let { session to it } }
        val (upcoming, started) = dated.sortedBy { it.second }.partition { !it.second.isBefore(currentTime) }

        val score = if (started.isEmpty()) 1.0 else 0.0
        val headline = when {
            started.isNotEmpty() ->
                "${started.size} of ${dated.size} preferred session(s) had already started at $nowLabel."
            dated.isEmpty() ->
                "Nothing was added to the schedule, so this rule passes vacuously — check the " +
                    "tool-call evaluators to see whether the agent was supposed to add anything."
            else ->
                "All ${dated.size} preferred session(s) start at or after $nowLabel."
        }

        val details = buildList {
            if (started.isNotEmpty()) {
                add("Violations — already under way when the user asked:")
                started.forEach { (session, startsAt) ->
                    add(
                        "  • started ${Duration.between(startsAt, currentTime).humanize()} earlier" +
                            " — $startsAt (${startsAt.asLocal()} local) — \"${session.title}\"",
                    )
                }
            }
            if (upcoming.isNotEmpty()) {
                add("Correctly still upcoming (${upcoming.size}), earliest first:")
                upcoming.take(MAX_LISTED).forEach { (session, startsAt) ->
                    add("  • $startsAt (${startsAt.asLocal()} local) — \"${session.title}\"")
                }
                if (upcoming.size > MAX_LISTED) add("  • … and ${upcoming.size - MAX_LISTED} more")
            }
            if (unreadable.isNotEmpty()) {
                add(
                    "⚠ ${unreadable.size} entry/entries under '$preferredSessionsKey' were not recognised as " +
                        "sessions and could not be checked: " +
                        unreadable.take(MAX_LISTED).joinToString { it.toString().take(80) },
                )
            }
            if (undated.isNotEmpty()) {
                add(
                    "⚠ ${undated.size} session(s) had an unparsable startsAt and could not be checked: " +
                        undated.take(MAX_LISTED).joinToString { "\"${it.title}\" (startsAt=\"${it.startsAt}\")" },
                )
            }
            if (started.isNotEmpty()) {
                add(
                    "If these look like sessions the agent was right to pick, check the clocks before the " +
                        "agent: '$currentTimeKey' must be a UTC instant, while the agent sees " +
                        "startsAtLocalDateTime in ${zone.id}. A local time written with a Z suffix shifts " +
                        "every comparison by the zone offset.",
                )
            }
        }

        return diagnostic(
            score = score,
            headline = headline,
            details = details,
            metadata = mapOf(
                "currentTime" to currentTime.toString(),
                "currentTimeLocal" to currentTime.asLocal(),
                "zone" to zone.id,
                "preferredSessionsCount" to dated.size,
                "startedSessionsCount" to started.size,
                "startedSessions" to started.map { (session, startsAt) ->
                    mapOf(
                        "startsAt" to session.startsAt,
                        "startsAtLocal" to startsAt.asLocal(),
                        "minutesLate" to Duration.between(startsAt, currentTime).toMinutes(),
                        "title" to session.title,
                    )
                },
                "upcomingSessions" to upcoming.map { (session, startsAt) ->
                    mapOf("startsAt" to session.startsAt, "startsAtLocal" to startsAt.asLocal(), "title" to session.title)
                },
                "uncheckedEntryCount" to (unreadable.size + undated.size),
            ),
        )
    }

    /** One [EvalResult] shape for every outcome, so a failure always explains itself over several lines. */
    private fun diagnostic(
        score: Double,
        headline: String,
        details: List<String> = emptyList(),
        metadata: Map<String, Any> = emptyMap(),
    ): EvalResult = EvalResult(
        name(),
        score,
        threshold(),
        score >= threshold(),
        (listOf(headline) + details).joinToString("\n"),
        metadata,
    )

    private fun Instant.asLocal(): String = LOCAL_FORMAT.format(atZone(zone))

    private fun Duration.humanize(): String {
        val hours = toHours()
        val minutes = toMinutes() % 60
        return when {
            hours > 0L && minutes > 0L -> "${hours}h${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    private fun Set<String>.describe(): String = sorted().joinToString().ifEmpty { "none" }

    private fun Any?.asSessionForOverlap(): SessionForOverlap? = when (this) {
        is ConferenceSession -> SessionForOverlap(title, startsAt)
        is Map<*, *> -> {
            val title = this["title"]?.toString()
            val startsAt = this["startsAt"]?.toString()
            if (title == null || startsAt == null) null else SessionForOverlap(title, startsAt)
        }
        else -> null
    }

    private data class SessionForOverlap(val title: String, val startsAt: String)

    companion object {
        private const val MAX_LISTED = 5
        private val LOCAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

@DokimosDsl
class StartedSessionOverlapEvaluatorDsl {
    var name: String = "Started Session Overlap"
    var preferredSessionsKey: String = "preferredSessions"
    var currentTimeKey: String = "currentTime"

    /** The zone the agent's `startsAtLocalDateTime` values are rendered in, used for readable failure output. */
    var zone: ZoneId = CONFERENCE_ZONE

    fun build(): StartedSessionOverlapEvaluator = StartedSessionOverlapEvaluator(
        evaluatorName = name,
        preferredSessionsKey = preferredSessionsKey,
        currentTimeKey = currentTimeKey,
        zone = zone,
    )
}

fun EvaluatorsDsl.startedSessionOverlap(block: StartedSessionOverlapEvaluatorDsl.() -> Unit = {}) {
    evaluator(StartedSessionOverlapEvaluatorDsl().apply(block).build())
}
