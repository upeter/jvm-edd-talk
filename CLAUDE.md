# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Sources for a conference talk on **Eval-Driven Development (EDD) on the JVM** using the
[Dokimos](https://github.com/dokimos-dev/dokimos) framework. The Spring AI app is a demo vehicle;
the *evals* are the point. Changes should keep the eval story legible for a live audience — the
`@Test` methods in `ChatEval.kt` are deliberately separated by blank-line gaps so they can be
revealed one at a time on stage. Preserve that spacing.

Offline Dokimos documentation and the upstream Claude Code eval skills are vendored in
[`docs/dokimos/`](docs/dokimos/README.md) — consult those before guessing at the Dokimos API.
This repo pins `dokimos.version = 0.27.0`; the vendored docs track upstream `main` (currently `0.28.0-SNAPSHOT`).

## Commands

All Maven commands run from `spring-ai/` (Java 21, Kotlin 2.2, Spring Boot 3.5.5, Spring AI 1.1.2):

```bash
./mvnw spring-boot:run                                    # app on :8082
./mvnw test                                               # all evals + tests
./mvnw test -Dtest=ChatEval                               # one eval class
./mvnw test -Dtest='ChatEval#should retrieve basic conference information'   # one eval (backtick names need quotes)
```

Infrastructure the evals and app depend on:

```bash
docker compose up -d          # repo root: pgvector on localhost:5430, seeded from docker-entrypoint-initdb.d/
cd dokimos && docker compose up -d   # Dokimos stats server + dashboard on localhost:8080
```

`OPENAI_API_KEY` is mandatory — the eval classes are annotated
`@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", ...)` and silently skip without it. A green
`./mvnw test` therefore does **not** mean the evals ran; check the output for skipped tests.
Langfuse/OTEL env vars (see README.md) are optional for the app but required for the feedback-triage flow.

The desktop client `chatclient-kmp/` is Gradle and ships only `gradlew.bat`; on macOS use a local
`gradle run`. It expects the server on `http://localhost:8082`.

## Architecture: the EDD loop

The repo is organized around one closed loop. Understanding it requires reading across modules:

**1. The application under evaluation** (`spring-ai/src/main/kotlin/dev/example/`)

A Spring AI chat app for a KotlinConf attendee assistant. `AIController` exposes `/chat`,
`/audio-chat`, `/audio-in-text-out-chat`, `/feedback`. `AiConfig` wires the `ChatClient` with a
`MessageChatMemoryAdvisor` (in-memory, keyed by `conversationId`) plus audio/image models.
`ConferenceTools` holds the `@Tool` functions — venue info, `searchSessions` (RAG over pgvector via
`SessionSearchRepository`), and add/remove/get preferred sessions backed by the in-memory
`SessionPreferenceRepository`.

**2. Tool-call observability for evals** (`Tools.kt`)

`ToolCallRecorder` + `RecordingToolCallback` capture every tool invocation (name, input JSON, output)
so eval tasks can assert on the agent's *trajectory*, not just its final text. Tool names are
declared as constants (`TOOL_ADD_PREFERRED_SESSIONS`, …) precisely so evaluators can reference them
without string drift. Call `toolCallbackRecorder.clear()` at the start of a task that inspects calls.

**3. Evals** (`spring-ai/src/test/kotlin/dev/example/edd/`)

`@SpringBootTest` classes that use the Dokimos Kotlin DSL. Each eval follows the same shape:

```kotlin
experiment {
    name = "..."                  // the key under which the server and baselines track this experiment
    dataset { example { input = ...; expected = ...; metadata(...) } }
    task { example -> mapOf("output" to ..., "toolCalls" to ..., "retrievedContext" to ...) }
    evaluators { llmJudge(judge) { ... }; faithfulness(judge) { ... }; toolCallEvaluator { ... } }
    reporter = serverReporter     // optional: pushes to the dashboard on :8080
}.run().print().assert()
```

The map returned by `task` is the contract with the evaluators — evaluators read named keys
(`output`, `toolCalls`, `retrievedContext`, `preferredSessions`, …) out of it, so adding an
evaluator usually means adding a key to the task's output map.

Terminal operations differ in meaning and are not interchangeable:
- `.assert()` — local `ExperimentResult` extension in `ChatEval.kt`; fails on any failing item.
- `.assertNoRegression("rag")` — Dokimos server-free regression gate. The name resolves
  `src/test/resources/dokimos/baselines/<name>.json` (relative to the module dir under Surefire);
  omitting it falls back to the experiment name. The first local run scaffolds the baseline and
  passes, so a green first run measures nothing — commit the file, then the gate has teeth.
  It fails on a statistically significant aggregate drop *or* any single item dropping more than
  `severityMargin` (0.15), so within-threshold noise does not flake the build. Re-baseline an
  intended change with `DOKIMOS_UPDATE_BASELINE=true ./mvnw test` (the `-D` property is unreliable
  through the IntelliJ runner).
- `.print()` — the human-readable console report in `EddEvalSupport.kt`, used for the live demo.

`ChatEval` also demonstrates multi-turn agent evaluation: `llmUser` (a persona-driven `SimulatedUser`)
+ `simulator { ... }.simulate()` produces a trajectory, scored by a `trajectoryEvaluator` with
weighted `TrajectoryEvaluationCriteria`.

**3b. Advanced agent evaluation: multi-turn trajectories and golden datasets** (`AdvancedAgentEval.kt`)

Built on 0.27 features: tool-aware trajectory scoring via `trajectoryEvaluator` with `toolCorrectness`
criteria, golden dataset generation via `GoldenGenerator`, and golden replay via `TaskCompletionEvaluator`.
**New eval methods go here, never into `ChatEval.kt`** — the latter's method order and spacing is a
stage-reveal sequence for the live talk.

**Permanently red test:** `AdvancedAgentEval#replay conversation goldens against their expected outcome`
is deliberately failing. It caught a real app bug in `ConferenceTools.addPreferenceSessions` →
`SessionPreferenceRepository.findBySessionTitle`'s title-matching logic (confirmed deterministic across
multiple LLM models). The product owner explicitly chose to keep this test red as talk material
demonstrating EDD catching a real bug. **Do not "fix" this test by editing the golden data, adjusting
thresholds, or patching the bug without explicit direction.**

**4. Custom evaluators** (`CustomEvaluators.kt`)

Domain evaluators are written as a triple: an `Evaluator` class, a `...Dsl` builder, and an
`EvaluatorsDsl.xxx { }` extension function that registers it — follow that pattern when adding one.
`ToolPresenceEvaluator` (was a specific tool called, tolerant of extra calls; fills the gap where
`toolCorrectness` set-matches the entire call list and penalizes any extra),
`ContainsEvaluator` (substring/expected match), `StartedSessionOverlapEvaluator` (domain rule: never
schedule a session that already started).

**5. Production feedback → new eval cases** (`langfuse/`, `src/notebooks/`)

The loop closes here. `LangfuseFeedbackClient` pulls traces and thumbs-down feedback from Langfuse;
`FeedbackTriageExporter` renders each into a markdown trace file plus a CSV row, and
`LlmFeedbackTriager` classifies the failure. Output lands in `src/notebooks/eval-data/feedback-triage/`,
which is what `FeedbackEvalTest` and the Kotlin notebooks (`edd.ipynb`, `feedback-triage.ipynb`)
consume — real-world failures become eval dataset entries.

## Conventions

- Kotlin backtick test names throughout; Kotest matchers (`shouldHaveSize`, `assertSoftly`, `withClue`)
  for plain assertions, Dokimos evaluators for LLM output.
- The judge model is built with `SpringAiSupport.asJudge(builder)` from the injected `ChatClient.Builder`.
- Model config lives in `application.properties` (temperature 0.4). Judge temperature is a
  baseline hazard the Dokimos docs call out: a non-zero judge temperature makes recorded scores drift
  for reasons unrelated to the code. Regenerate baselines deliberately, never to make a build go green.
  **Note:** The model alias `gpt-5-chat-latest` in `application.properties` is deprecated by OpenAI;
  real evals require a manual `-Dspring.ai.openai.chat.options.model=<current-model>` override at test time
  (e.g., `gpt-4o-mini`). This is not part of the Dokimos upgrade.
- The `baselines/rag.json` regression baseline is committed and the `.assertNoRegression("rag")`
  gate in `ChatEval` is live.
