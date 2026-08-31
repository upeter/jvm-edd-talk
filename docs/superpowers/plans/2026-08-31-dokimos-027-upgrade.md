# Dokimos 0.27.0 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the talk repo to Dokimos 0.27.0, replace the hand-rolled `ToolCallEvaluator` with the built-in agent evaluators plus one narrow custom evaluator, and add two 0.27 features as new talk material.

**Architecture:** All work is in the `spring-ai` Maven module. Shared eval helpers move out of `ChatEval.kt`/`EDDUtils.kt` into a new `EddEvalSupport.kt` so the eval classes stay readable on a projector. Tool calls stop being ad-hoc `Map`s and become typed `dev.dokimos.core.agents.ToolCall`s, which is what every built-in agent evaluator consumes. New 0.27 material lands in a separate `AdvancedAgentEval.kt` so `ChatEval.kt`'s method order — the stage reveal sequence — is untouched.

**Tech Stack:** Kotlin 2.2, Java 21, Spring Boot 3.5.5, Spring AI 1.1.2, Dokimos 0.27.0, JUnit 5, Kotest assertions, Maven.

## Global Constraints

- Every shell command must be prefixed with `source ~/.bash_profile &&` — SDKMAN tools (including the correct `git` and `java`) are only on `PATH` afterwards.
- All Maven commands run from `/Users/urs/development/github/ai/kotlin-edd-talk/spring-ai`.
- Dokimos version is exactly `0.27.0`. It is already in `~/.m2`, so no network fetch is needed.
- Kotlin test names use backticks; quote them when passing to `-Dtest`.
- `ChatEval.kt`'s method order and the blank-line gaps between its `@Test` methods are the talk's stage-reveal sequence. Preserve both. Do not add new test methods to this class.
- Evals annotated `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", ...)` skip silently without the key. A green run without the key proves nothing — always check for skips.
- Tasks 1–4 are fully offline and cost nothing. Tasks 5–8 call the OpenAI API and cost real tokens.
- `PlanQualityEvaluator` / `PlanAdherenceEvaluator` are explicitly out of scope (see the spec's resolved decision).

## File Structure

| File | Responsibility |
| --- | --- |
| `spring-ai/pom.xml` | Modify: dokimos version property. |
| `src/test/kotlin/dev/example/edd/EddEvalSupport.kt` | Create: all shared eval helpers — tool-call adaptation, judge, reporter, tool definitions, printing, asserting. |
| `src/test/kotlin/dev/example/edd/EddEvalSupportTest.kt` | Create: offline unit tests for the pure helpers. |
| `src/test/kotlin/dev/example/edd/CustomEvaluators.kt` | Modify: drop `ToolCallEvaluator`, add `ToolPresenceEvaluator`. |
| `src/test/kotlin/dev/example/edd/ToolPresenceEvaluatorTest.kt` | Create: offline unit tests for the new evaluator. |
| `src/test/kotlin/dev/example/edd/EDDUtils.kt` | Delete: `print()` moves into `EddEvalSupport.kt`. |
| `src/test/kotlin/dev/example/edd/ChatEval.kt` | Modify: call sites only. |
| `src/test/kotlin/dev/example/edd/AdvancedAgentEval.kt` | Create: the two new 0.27 evals. |
| `src/test/resources/datasets/kotlinconf-goldens.json` | Create: generated, reviewed, committed golden dataset. |
| `src/test/resources/dokimos/baselines/` | Modify: regenerate `rag.json`, remove the orphan. |
| `CLAUDE.md`, `docs/dokimos/README.md` | Modify: version and baseline facts. |

---

### Task 1: Upgrade to Dokimos 0.27.0

The 0.22 → 0.27 delta was verified to be purely additive, so this should compile untouched. Anything that breaks is a finding worth reporting, not a planned edit.

**Files:**
- Modify: `spring-ai/pom.xml:18`

**Interfaces:**
- Consumes: nothing.
- Produces: Dokimos 0.27.0 on the test classpath, making `toolCorrectness`, `goldenGenerator`, `scenarioSeed`, `assistant(content, toolCalls)`, and `dev.dokimos.core.agents.ToolCalls` available to later tasks.

- [ ] **Step 1: Confirm 0.27.0 is present locally**

```bash
source ~/.bash_profile && ls ~/.m2/repository/dev/dokimos/dokimos-kotlin/0.27.0/
```

Expected: a listing containing `dokimos-kotlin-0.27.0.jar`.

- [ ] **Step 2: Bump the version property**

In `spring-ai/pom.xml`, change:

```xml
<dokimos.version>0.22.0</dokimos.version>
```

to:

```xml
<dokimos.version>0.27.0</dokimos.version>
```

- [ ] **Step 3: Verify everything still compiles**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw -q test-compile
```

Expected: BUILD SUCCESS with no source changes. If it fails, stop and report the exact compiler error — the upgrade was expected to be source-compatible, so a failure invalidates an assumption in the spec.

- [ ] **Step 4: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add spring-ai/pom.xml && git commit -m "update: upgrade dokimos to 0.27.0"
```

---

### Task 2: Tool-call adaptation helpers

`ToolCallRecorder` captures `CapturedToolCall(toolName, inputJson, output)`. Every built-in agent evaluator wants `dev.dokimos.core.agents.ToolCall` under the `"toolCalls"` key, with arguments as a parsed `Map`. This task builds that bridge and tests it offline.

**Files:**
- Create: `spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupport.kt`
- Test: `spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupportTest.kt`

**Interfaces:**
- Consumes: `dev.example.CapturedToolCall(toolName: String, inputJson: String, output: String)` from `Tools.kt`.
- Produces:
  - `fun List<CapturedToolCall>.asToolCalls(): List<ToolCall>`
  - `fun expectedToolCalls(vararg names: String): List<Map<String, Any>>`

- [ ] **Step 1: Write the failing test**

Create `spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupportTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest=EddEvalSupportTest
```

Expected: compilation failure — `asToolCalls`/`expectedToolCalls` are unresolved references.

- [ ] **Step 3: Write the implementation**

Create `spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupport.kt`:

```kotlin
package dev.example.edd

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dokimos.core.agents.ToolCall
import dev.example.CapturedToolCall

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

/** Expected tool calls for the name-based matchers — only the names matter. */
fun expectedToolCalls(vararg names: String): List<Map<String, Any>> = names.map { mapOf("name" to it) }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest=EddEvalSupportTest
```

Expected: PASS, 3 tests run, 0 skipped. These tests need no API key.

- [ ] **Step 5: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupport.kt spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupportTest.kt && git commit -m "add: tool call adapter for dokimos built-in agent evaluators"
```

---

### Task 3: Replace ToolCallEvaluator with ToolPresenceEvaluator

`toolCorrectness` scores the F1 of tool *name sets*, so an extra legitimate call lowers the score. The old `ToolCallEvaluator` asked only "was tool X called somewhere". That looser check still has no built-in equivalent, so it survives as a narrow, well-documented evaluator while the strict cases move to the built-in.

**Files:**
- Modify: `spring-ai/src/test/kotlin/dev/example/edd/CustomEvaluators.kt` (delete lines 39–180: `ToolCallEvaluator`, `ToolCallEvaluatorDsl`, `EvaluatorsDsl.toolCallEvaluator`; add `ToolPresenceEvaluator` and its DSL)
- Test: `spring-ai/src/test/kotlin/dev/example/edd/ToolPresenceEvaluatorTest.kt`

**Interfaces:**
- Consumes: `asToolCalls()` from Task 2 (tests only).
- Produces: `EvaluatorsDsl.toolPresence(block: ToolPresenceEvaluatorDsl.() -> Unit)`, where `ToolPresenceEvaluatorDsl` has `var name: String`, `var expectedToolName: String?`, `var toolOutputKey: String?`. Reads actual calls from the `"toolCalls"` output key.
- Removes: `EvaluatorsDsl.toolCallEvaluator` — Task 5 migrates its three call sites.

- [ ] **Step 1: Write the failing test**

Create `spring-ai/src/test/kotlin/dev/example/edd/ToolPresenceEvaluatorTest.kt`:

```kotlin
package dev.example.edd

import dev.dokimos.core.agents.ToolCall
import dev.dokimos.kotlin.core.EvalTestCase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ToolPresenceEvaluatorTest {

    private fun toolCall(name: String, result: String = "ok"): ToolCall =
        ToolCall.builder().name(name).arguments(emptyMap<String, Any>()).result(result).build()

    @Test
    fun `passes when the expected tool was called among others`() {
        val evaluator = ToolPresenceEvaluator("Venue Tool", "general-venue-information-kotlinconf")
        val testCase = EvalTestCase(
            actualOutputs = mapOf(
                "toolCalls" to listOf(
                    toolCall("conference-session-search"),
                    toolCall("general-venue-information-kotlinconf")
                )
            )
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 1.0
        result.success() shouldBe true
    }

    @Test
    fun `fails when the expected tool was never called`() {
        val evaluator = ToolPresenceEvaluator("Venue Tool", "general-venue-information-kotlinconf")
        val testCase = EvalTestCase(
            actualOutputs = mapOf("toolCalls" to listOf(toolCall("conference-session-search")))
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 0.0
        result.success() shouldBe false
    }

    @Test
    fun `fails when the tool was called but its result lacks the expected text`() {
        val evaluator = ToolPresenceEvaluator(
            "Venue Tool",
            "general-venue-information-kotlinconf",
            toolOutputKey = "venue"
        )
        val testCase = EvalTestCase(
            actualOutputs = mapOf("toolCalls" to listOf(toolCall("general-venue-information-kotlinconf", "Berlin"))),
            expectedOutputs = mapOf("venue" to "Muenchen")
        )

        val result = evaluator.evaluate(testCase)

        result.score() shouldBe 0.0
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest=ToolPresenceEvaluatorTest
```

Expected: compilation failure — `ToolPresenceEvaluator` is an unresolved reference.

- [ ] **Step 3: Add the evaluator**

In `CustomEvaluators.kt`, delete `ToolCallEvaluator` (lines 39–152), `ToolCallEvaluatorDsl` (154–175), and `EvaluatorsDsl.toolCallEvaluator` (177–180). In their place, add:

```kotlin
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
) : BaseEvaluator(evaluatorName, 1.0, listOf(EvalTestCaseParam.ACTUAL_OUTPUT)) {

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
```

Add this import to the top of the file:

```kotlin
import dev.dokimos.core.agents.ToolCall
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest=ToolPresenceEvaluatorTest
```

Expected: PASS, 3 tests. `ChatEval.kt` will not compile yet — it still calls `toolCallEvaluator`. That is expected and is fixed in Task 5. If Maven fails on `ChatEval.kt` compilation, proceed to Step 5 anyway and let Task 5 restore a green build.

- [ ] **Step 5: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add spring-ai/src/test/kotlin/dev/example/edd/CustomEvaluators.kt spring-ai/src/test/kotlin/dev/example/edd/ToolPresenceEvaluatorTest.kt && git commit -m "add: ToolPresenceEvaluator replacing hand-rolled ToolCallEvaluator"
```

---

### Task 4: Consolidate the remaining shared helpers

Moves the judge, reporter, printer, and asserter out of the eval classes, and adds the one piece of the official Spring AI adapter that *is* usable here.

**Files:**
- Modify: `spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupport.kt`
- Delete: `spring-ai/src/test/kotlin/dev/example/edd/EDDUtils.kt`

**Interfaces:**
- Consumes: `AIController.interceptedTools` (a public `List<ToolCallback>` field on the controller).
- Produces:
  - `fun springAiJudge(builder: ChatClient.Builder): JudgeLM`
  - `fun dokimosReporter(): DokimosServerReporter`
  - `fun conferenceToolDefinitions(controller: AIController): List<ToolDefinition>` (Dokimos `ToolDefinition`)
  - `fun ExperimentResult.print(): ExperimentResult`
  - `fun ExperimentResult.assert()`

- [ ] **Step 1: Append the helpers to EddEvalSupport.kt**

Add to `spring-ai/src/test/kotlin/dev/example/edd/EddEvalSupport.kt`:

```kotlin
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
fun ExperimentResult.assert() {
    runResults.filter { it.failCount() > 0 }.takeIf { it.isNotEmpty() }?.let {
        fail(it.joinToString { it.itemResults().joinToString("\n") })
    }
}
```

Then move the entire body of `print()` from `EDDUtils.kt` into this file verbatim, and add these imports:

```kotlin
import dev.dokimos.core.ExperimentResult
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.agents.ToolDefinition
import dev.dokimos.server.client.DokimosServerReporter
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import io.kotest.assertions.AssertionErrorBuilder.Companion.fail
import org.springframework.ai.chat.client.ChatClient
```

- [ ] **Step 2: Delete the old utils file**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git rm spring-ai/src/test/kotlin/dev/example/edd/EDDUtils.kt
```

- [ ] **Step 3: Verify the helpers compile**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw -q test-compile
```

Expected: the only remaining errors are the unresolved `toolCallEvaluator` calls in `ChatEval.kt` (fixed next). If `conferenceToolDefinitions` fails because `interceptedTools` is not accessible or `it.toolDefinition` has a different name, report the exact error rather than guessing — that field is what `AIController.kt:78` builds.

- [ ] **Step 4: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add -A spring-ai/src/test/kotlin/dev/example/edd && git commit -m "update: consolidate shared eval helpers into EddEvalSupport"
```

---

### Task 5: Migrate the ChatEval call sites

Three `toolCallEvaluator` uses become built-in `toolCorrectness`, and the task blocks emit typed tool calls. This restores a green build.

**Files:**
- Modify: `spring-ai/src/test/kotlin/dev/example/edd/ChatEval.kt`

**Interfaces:**
- Consumes: `asToolCalls()`, `expectedToolCalls()`, `springAiJudge()`, `dokimosReporter()`, `print()`, `assert()` from Tasks 2 and 4; `toolPresence { }` from Task 3.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace the class-level judge, reporter, and local assert**

Delete the local `fun ExperimentResult.assert()` at the bottom of the file (now in `EddEvalSupport.kt`). Replace the field declarations:

```kotlin
    val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    val serverReporter = DokimosServerReporter.builder()
        .serverUrl("http://localhost:8080")
        .projectName("kotlinconf-chat-app-evals")
        .build()
```

with:

```kotlin
    val judge: JudgeLM = springAiJudge(builder)

    val serverReporter = dokimosReporter()
```

In `should retrieve basic conference information and evaluate tone`, replace the inline `reporter = DokimosServerReporter.builder()...build()` with `reporter = serverReporter`, and replace `llmJudge(judge = SpringAiSupport.asJudge(builder))` with `llmJudge(judge = judge)`.

- [ ] **Step 2: Migrate the venue eval to typed tool calls and toolCorrectness**

In `should retrieve accurate general venue information`, replace the `toolCalls` construction in the task block:

```kotlin
                val toolCalls = toolCallbackRecorder.getCalls().map {
                    mapOf("toolName" to it.toolName,
                        "toolInput" to it.inputJson,
                        "toolOutput" to it.output)
                }
```

with:

```kotlin
                val toolCalls = toolCallbackRecorder.getCalls().asToolCalls()
```

Add `toolCallbackRecorder.clear()` as the first line of the task block, so calls from a previous item never leak in. Add the expected calls to the dataset — inside each of the two `example { }` blocks, after the `metadata(...)` lines:

```kotlin
                    expected("toolCalls", expectedToolCalls(TOOL_GENERAL_VENUE_INFORMATION_KOTLINCONF))
```

Then replace the evaluator:

```kotlin
                toolCallEvaluator {
                    expectedToolName = TOOL_GENERAL_VENUE_INFORMATION_KOTLINCONF
                    toolOutputKey = "toolOutput"
                }
```

with:

```kotlin
                toolCorrectness {}
```

- [ ] **Step 3: Migrate the started-sessions eval**

In `should not add already started sessions to preferences`, replace the task block's `toolCalls` construction with `val toolCalls = toolCallbackRecorder.getCalls().asToolCalls()` (the `toolCallbackRecorder.clear()` call is already the first line there).

This agent legitimately calls more tools than the two named ones, so use the presence evaluator rather than `toolCorrectness`. Replace:

```kotlin
                toolCallEvaluator {
                    name = "Add Preferred Sessions Tool Call"
                    expectedToolName = TOOL_ADD_PREFERRED_SESSIONS
                }
                toolCallEvaluator {
                    name = "Search Sessions Tool Call"
                    expectedToolName = TOOL_CONFERENCE_SESSION_SEARCH
                }
```

with:

```kotlin
                toolPresence {
                    name = "Add Preferred Sessions Tool Call"
                    expectedToolName = TOOL_ADD_PREFERRED_SESSIONS
                }
                toolPresence {
                    name = "Search Sessions Tool Call"
                    expectedToolName = TOOL_CONFERENCE_SESSION_SEARCH
                }
```

- [ ] **Step 4: Clean up imports and verify compilation**

Remove now-unused imports (`dev.dokimos.server.client.DokimosServerReporter`, and `dev.dokimos.springai.SpringAiSupport` if no longer referenced). Then:

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw -q test-compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Verify the offline unit tests still pass**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest='EddEvalSupportTest+ToolPresenceEvaluatorTest'
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Run one real eval end to end**

Requires `OPENAI_API_KEY` and `docker compose up -d` at the repo root.

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest='ChatEval#should retrieve accurate general venue information'
```

Expected: the test runs (not skipped) and the printed report shows a `Tool Correctness` score. A score below 1.0 means the agent called tools beyond the single expected one — if so, switch that eval to `toolPresence` as well and note it in the commit message.

- [ ] **Step 7: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add spring-ai/src/test/kotlin/dev/example/edd/ChatEval.kt && git commit -m "update: migrate ChatEval to built-in tool evaluators and typed tool calls"
```

---

### Task 6: Tool calls in the multi-turn trajectory

0.27's `Message` can carry the tool calls made in a turn, so the trajectory evaluator sees tool use rather than text alone.

**Files:**
- Create: `spring-ai/src/test/kotlin/dev/example/edd/AdvancedAgentEval.kt`

**Interfaces:**
- Consumes: `asToolCalls()`, `springAiJudge()` from Tasks 2 and 4.
- Produces: the `AdvancedAgentEval` class, extended by Task 7.

- [ ] **Step 1: Create the class with a tool-aware multi-turn eval**

Create `spring-ai/src/test/kotlin/dev/example/edd/AdvancedAgentEval.kt`:

```kotlin
package dev.example.edd

import dev.dokimos.core.JudgeLM
import dev.dokimos.core.conversation.AggregationStrategy
import dev.dokimos.core.conversation.ConversationalApplication
import dev.dokimos.core.conversation.SimulatedUser
import dev.dokimos.core.conversation.TrajectoryEvaluationCriteria
import dev.dokimos.core.conversation.TrajectoryEvaluator
import dev.dokimos.kotlin.core.EvalTestCase
import dev.dokimos.kotlin.dsl.conversation.assistantMessage
import dev.dokimos.kotlin.dsl.conversation.llmUser
import dev.dokimos.kotlin.dsl.conversation.simulator
import dev.dokimos.kotlin.dsl.conversation.trajectoryEvaluator
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * Evals built on Dokimos 0.27 features. Kept separate from [ChatEval], whose method order and
 * spacing are the talk's reveal sequence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class AdvancedAgentEval @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val tools: ConferenceTools,
    val toolCallbackRecorder: ToolCallRecorder,
) {

    val judge: JudgeLM = springAiJudge(builder)

    @Test
    fun `multiturn trajectory carries the tool calls the agent made`() {
        val user: SimulatedUser = llmUser(judge) {
            persona = "Kotlin backend developer planning a conference schedule"
            behaviorGuidelines = """
                - Asks about backend and AI sessions, then asks to add them to the schedule.
                - Keeps going until a few sessions are on the schedule.
            """
        }
        val conversationId = UUID.randomUUID().toString()

        // Each turn: clear the recorder, answer, then attach the calls made for THIS turn.
        val chatApp = ConversationalApplication { trajectory ->
            toolCallbackRecorder.clear()
            val response = controller.chat(ChatMessage(trajectory.toText(), conversationId)).orEmpty()
            assistantMessage(response, toolCallbackRecorder.getCalls().asToolCalls())
        }

        val trajectory = simulator {
            simulatedUser = user
            application = chatApp
            maxTurns = 4
            scenario = "User builds a conference schedule with the assistant"
            initialMessage = "Hi, I'm looking for AI sessions"
        }.simulate()

        println("=== Conversation ===")
        println(trajectory.toText())

        val evaluator: TrajectoryEvaluator = trajectoryEvaluator(judge) {
            name = "Tool-Aware Schedule Trajectory"
            threshold = 0.7
            criteria(
                listOf(
                    TrajectoryEvaluationCriteria.goalCompletion(),
                    TrajectoryEvaluationCriteria.helpfulness()
                )
            )
            aggregationStrategy = AggregationStrategy.WEIGHTED_MEAN
        }

        val result = evaluator.evaluate(EvalTestCase(actualOutputs = mapOf("trajectory" to trajectory)))

        println("Overall Score: ${"%.2f".format(result.score())}")
        println("Reason: ${result.reason()}")
        assert(result.success()) { "Trajectory scored ${result.score()}: ${result.reason()}" }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw -q test-compile
```

Expected: BUILD SUCCESS. If `assistantMessage` is unresolved, confirm the import path against the 0.27 `ConversationDsl.kt` — it is a top-level function in `dev.dokimos.kotlin.dsl.conversation`.

- [ ] **Step 3: Run it**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest='AdvancedAgentEval#multiturn trajectory carries the tool calls the agent made'
```

Expected: the conversation prints, the score prints, the test passes. Confirm it was not skipped.

- [ ] **Step 4: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add spring-ai/src/test/kotlin/dev/example/edd/AdvancedAgentEval.kt && git commit -m "add: tool-aware multiturn trajectory eval on dokimos 0.27"
```

---

### Task 7: Generate and replay conversation goldens

`GoldenGenerator` runs scenario seeds through the simulator and turns each conversation into a dataset example. Uses **scripted** seeds (fixed user turns, no LLM on the user side) so the generated suite is cheap and stable.

**Files:**
- Modify: `spring-ai/src/test/kotlin/dev/example/edd/AdvancedAgentEval.kt`
- Create: `spring-ai/src/test/resources/datasets/kotlinconf-goldens.json` (generated, then committed)

**Interfaces:**
- Consumes: `AdvancedAgentEval` from Task 6.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the generator method**

Add to `AdvancedAgentEval.kt`, and add these imports:

```kotlin
import dev.dokimos.kotlin.dsl.conversation.goldenGenerator
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.core.conversation.Message
import org.junit.jupiter.api.Disabled
import java.nio.file.Path
```

```kotlin
    /**
     * Regenerates the committed golden suite. Disabled by default: it calls the model and rewrites a
     * reviewed artifact. Remove @Disabled, run it once, review the diff, commit.
     */
    @Test
    @Disabled("Run manually to regenerate src/test/resources/datasets/kotlinconf-goldens.json")
    fun `generate conversation goldens`() {
        val conversationId = UUID.randomUUID().toString()
        val chatApp = ConversationalApplication { trajectory ->
            Message.assistant(controller.chat(ChatMessage(trajectory.toText(), conversationId)).orEmpty())
        }

        val generator = goldenGenerator {
            application = chatApp
            name = "kotlinconf-goldens"
            description = "Scripted conference-assistant conversations, replayed as a regression suite"

            seed {
                scenario = "First-time attendee asks about the venue, then the ticket price"
                userTurns(
                    listOf(
                        "Where is KotlinConf 2026 held?",
                        "And what does a regular ticket cost?"
                    )
                )
                expectedOutcome = "The assistant gives the venue address and then the regular ticket price"
                // TaskCompletionEvaluator reads its tasks from metadata under "tasks", as a List<String>.
                // expectedOutcome alone is a String under a different key, so it cannot be used directly.
                metadata(
                    "tasks",
                    listOf(
                        "State the KotlinConf 2026 venue address",
                        "State the regular ticket price"
                    )
                )
            }

            seed {
                scenario = "Attendee searches for AI sessions and adds one to their schedule"
                userTurns(
                    listOf(
                        "Which sessions are about AI frameworks?",
                        "Add the first one to my preferred sessions"
                    )
                )
                expectedOutcome = "The assistant lists AI sessions and confirms one was added to the schedule"
                metadata(
                    "tasks",
                    listOf(
                        "List conference sessions about AI frameworks",
                        "Confirm that a session was added to the preferred schedule"
                    )
                )
            }
        }

        generator.write(Path.of("src/test/resources/datasets/kotlinconf-goldens.json"))
    }
```

- [ ] **Step 2: Generate the dataset**

Temporarily remove the `@Disabled` annotation, then:

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest='AdvancedAgentEval#generate conversation goldens'
```

Expected: PASS, and `src/test/resources/datasets/kotlinconf-goldens.json` exists with two examples (`golden-0`, `golden-1`). Restore the `@Disabled` annotation afterwards.

- [ ] **Step 3: Review the generated file**

Open the JSON and read it — it is a reviewed artifact like a golden file, not build output. Each example should have `inputs.input` (the full transcript), `expectedOutputs.output` (the assistant's last reply, present because these are scripted seeds), and `metadata` with `scenario`, `turnCount`, and `expectedOutcome`. If a `metadata.error` key is present, the seed failed to run — fix it before committing.

- [ ] **Step 4: Add the replay eval**

Add to `AdvancedAgentEval.kt`. This grades the recorded transcript against the tasks recorded in each golden's metadata. Per the Dokimos docs, never send `inputs.input` back to the application as a prompt — it is the whole transcript, assistant replies included, so you would hand the model the answer it is being graded on.

`TaskCompletionEvaluator` reads `metadata["tasks"]` (a `List<String>`) and `inputs["input"]` (the dialog). Both already come from the golden example, so the task block only has to surface the transcript as `output` for the printed report.

```kotlin
    @Test
    fun `replay conversation goldens against their expected outcome`() {
        val goldens = Dataset.fromJson(Path.of("src/test/resources/datasets/kotlinconf-goldens.json"))

        experiment {
            name = "KotlinConf Conversation Goldens"
            dataset(goldens)
            task { example -> mapOf("output" to example.input()) }
            evaluators {
                taskCompletion(judge) {
                    name = "Goal Reached"
                    threshold = 0.7
                }
            }
        }.run().print().assert()
    }
```

Add the import:

```kotlin
import dev.dokimos.core.Dataset
```

- [ ] **Step 5: Run the replay eval**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest='AdvancedAgentEval#replay conversation goldens against their expected outcome'
```

Expected: PASS with a `Goal Reached` score per golden.

`Dataset.fromJson(Path)` is verified to exist in 0.27 and `throws IOException`, so the test method needs no special handling in Kotlin (checked exceptions are not enforced). If the evaluator throws `TaskCompletionEvaluator requires 'tasks' in metadata`, the experiment runner is not carrying example metadata into the `EvalTestCase` — in that case set `tasksKey` to a key you place in the task's output map instead, and note the finding.

- [ ] **Step 6: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add spring-ai/src/test/kotlin/dev/example/edd/AdvancedAgentEval.kt spring-ai/src/test/resources/datasets/kotlinconf-goldens.json && git commit -m "add: generated conversation goldens and replay eval"
```

---

### Task 8: Fix and regenerate the regression baselines

Changing an evaluator set shifts the significance test's p-values, so every gated experiment needs a fresh baseline. Two pre-existing problems get fixed here too.

**Files:**
- Delete: `spring-ai/src/test/resources/dokimos/baselines/tone-evals.json`
- Create: `spring-ai/src/test/resources/dokimos/baselines/rag.json` (generated, reviewed, committed)

**Interfaces:**
- Consumes: the migrated evals from Task 5.
- Produces: a committed, meaningful regression gate.

- [ ] **Step 1: Remove the orphaned baseline**

`tone-evals.json` records the `"KotlinConf Tone Evals"` experiment, but that experiment calls `.assert()`, not `assertNoRegression` — nothing reads this file.

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git rm spring-ai/src/test/resources/dokimos/baselines/tone-evals.json
```

- [ ] **Step 2: Generate the missing rag baseline**

`ChatEval.should retrieve basic conference information` gates on `assertNoRegression("rag")`, but `rag.json` has never been committed, so the gate has only ever been in first-run scaffold mode.

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && DOKIMOS_UPDATE_BASELINE=true ./mvnw test -Dtest='ChatEval#should retrieve basic conference information'
```

Expected: the run writes `src/test/resources/dokimos/baselines/rag.json`.

- [ ] **Step 3: Review the generated baseline**

Open `rag.json` and confirm the recorded scores are ones you are willing to defend as "correct today" — every future regression is measured against them. Check the `evaluators` entries match the current evaluator set.

- [ ] **Step 4: Verify the gate now has teeth**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test -Dtest='ChatEval#should retrieve basic conference information'
```

Expected: PASS, and the output reports a comparison against the baseline rather than "Baseline created at ...".

- [ ] **Step 5: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add -A spring-ai/src/test/resources/dokimos/baselines && git commit -m "update: commit rag regression baseline and drop orphaned tone baseline"
```

---

### Task 9: Update the documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/dokimos/README.md`

**Interfaces:**
- Consumes: the finished state from Tasks 1–8.
- Produces: nothing.

- [ ] **Step 1: Update CLAUDE.md**

Apply these changes:
- The `docs/dokimos/` paragraph: the repo now pins `0.27.0`, so drop the version-skew warning about 0.22.0 (the vendored docs track `main` at `0.28.0-SNAPSHOT`, which is still worth a note).
- The custom-evaluator paragraph: replace `ToolCallEvaluator` with `ToolPresenceEvaluator` and state why it exists (`toolCorrectness` set-matches the whole call list).
- The `EDDUtils.kt` reference in the terminal-operations list: `print()` now lives in `EddEvalSupport.kt`.
- The final bullet about the orphaned `tone-evals.json`: delete it — Task 8 fixed it. Replace with a line stating that `rag.json` is committed and the gate is live.
- Add `AdvancedAgentEval.kt` to the eval-architecture section as the home for 0.27 features, and note that new eval methods go there, never into `ChatEval.kt`.

- [ ] **Step 2: Update docs/dokimos/README.md**

In the "Start here" preamble, change the version line to state the repo pins `0.27.0` and the vendored docs track upstream `main`.

- [ ] **Step 3: Verify no stale references remain**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && grep -rn "ToolCallEvaluator\|EDDUtils\|0\.22\.0\|tone-evals" CLAUDE.md docs/dokimos/README.md spring-ai/src
```

Expected: no matches.

- [ ] **Step 4: Full verification run**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk/spring-ai && ./mvnw test
```

Expected: all tests pass, no test silently skipped (other than the `@Disabled` golden generator). Report the actual counts.

- [ ] **Step 5: Commit**

```bash
source ~/.bash_profile && cd /Users/urs/development/github/ai/kotlin-edd-talk && git add CLAUDE.md docs/dokimos/README.md && git commit -m "update: document dokimos 0.27 upgrade and evaluator changes"
```
