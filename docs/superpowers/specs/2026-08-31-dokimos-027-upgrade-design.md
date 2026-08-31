# Dokimos 0.22.0 → 0.27.0 upgrade and evaluator rewrite

**Date:** 2026-08-31
**Repo:** `kotlin-edd-talk` (`spring-ai/` module)
**Reference:** `/Users/urs/development/github/ai/ai-training/gen-ai-jvm-training/solutions/edd-solutions` (already on 0.27.0)

## Goal

Move the talk repo to Dokimos 0.27.0, replace the hand-rolled tool evaluator with the built-in
agent evaluators, and add the 0.27 capabilities that make good talk material.

## Findings that shape the design

Established by diffing the `0.22.0` and `0.27.0` sources jars from `~/.m2`, not from release notes.

1. **The built-in tool evaluators were already in 0.22.0.** `toolCorrectness`, `toolTrajectory`,
   `toolCallValidity`, `toolEfficiency`, `toolError`, `toolArgumentHallucination`,
   `toolNameReliability`, `toolDescriptionReliability` all exist in the 0.22.0 Kotlin DSL. The
   rewrite of `ToolCallEvaluator` is worth doing, but it is not caused by the version bump.

2. **The 0.22 → 0.27 delta is purely additive.** No removals, no changed signatures across
   `dokimos-core`, `dokimos-kotlin`, `dokimos-spring-ai`, `dokimos-server-client`. New:
   `PlanQualityEvaluator`, `PlanAdherenceEvaluator` (+ DSL), `GoldenGenerator`, `ScenarioSeed`
   (+ `scenarioSeed`/`goldenGenerator` DSL), `Message` carrying tool calls (+ `assistant(content,
   toolCalls)` DSL), and `ToolCalls.coerce()` for clearer malformed-dataset errors. The upgrade
   itself is expected to be a version bump that compiles unchanged.

3. **0.27.0 is already in the local `~/.m2`**, so the bump needs no network access.

4. **`toolCorrectness` is stricter than the current `ToolCallEvaluator`.** It scores the F1 of tool
   *name sets* against an expected set (`NAMES_ONLY` default; `NAMES_AND_ORDER` and `NAMES_AND_ARGS`
   also available), so an extra legitimate call lowers the score. `toolTrajectory` wants an exact
   name+argument sequence. The current evaluator asks only "was tool X called somewhere", which
   neither built-in expresses. The reference repo hit the same wall and kept a custom
   `ToolPresenceEvaluator` for it.

5. **Dokimos ships a Spring AI adapter, but it cannot be used end to end here.**
   `SpringAiSupport.toToolCalls` / `toAgentTrace` consume Spring AI `AssistantMessage` and
   `ToolResponseMessage` objects. `AIController.chat()` executes tools *inside* the `ChatClient`
   (`toolCallbacks(interceptedTools)`) and returns `.content()` as a `String`, so those objects never
   reach the eval. The `ToolCallRecorder` stays, and a thin `CapturedToolCall → ToolCall` mapping
   bridges to the built-ins. `SpringAiSupport.toToolDefinitions(...)` *is* usable as-is and is worth
   adopting — the validity and reliability evaluators need the tool definitions.

6. **`PlanQuality` / `PlanAdherence` need a `reasoningSteps` output the app does not produce.** See
   the open decision below.

## Design

### 1. Version bump

`spring-ai/pom.xml`: `<dokimos.version>0.22.0</dokimos.version>` → `0.27.0`. Expected to compile
with no source changes; anything that does break is a finding, not a planned edit.

### 2. Shared eval helpers — `EddEvalSupport.kt`

New file in `dev.example.edd`, following the reference repo, so `ChatEval.kt` stays a clean sequence
of `experiment { }` blocks for stage projection. It holds:

- `List<CapturedToolCall>.asToolCalls(): List<ToolCall>` — parses `inputJson` into the arguments map
  so argument-level evaluators can inspect it
- `expectedToolCalls(vararg names: String)` — expected-call rows for the name matchers
- `conferenceToolDefinitions()` — wraps `SpringAiSupport.toToolDefinitions(...)`, new relative to the
  reference repo, unlocking `toolCallValidity` / `toolNameReliability` / `toolDescriptionReliability`
- `springAiJudge(builder)` — moved from the `judge` field in `ChatEval`
- `dokimosReporter()` — moved from the inline `DokimosServerReporter.builder()` calls, which are
  currently duplicated three times in `ChatEval.kt`
- `print()` — moved from `EDDUtils.kt` (which is then deleted)
- `assert()` — moved from the bottom of `ChatEval.kt`

Task blocks stop building ad-hoc `mapOf("toolName" to …, "toolInput" to …, "toolOutput" to …)` maps
and emit typed `ToolCall`s under `"toolCalls"` instead.

### 3. Evaluator rewrite — `CustomEvaluators.kt`

| Evaluator | Action |
| --- | --- |
| `ToolCallEvaluator` + DSL | **Delete.** Replaced by `toolCorrectness` or `ToolPresenceEvaluator`. |
| `ToolPresenceEvaluator` + DSL | **Add.** "Tool X was called somewhere", tolerant of extra calls. |
| `ContainsEvaluator` | **Keep.** No built-in equivalent (`exactMatch` is too strict). |
| `StartedSessionOverlapEvaluator` | **Keep.** Domain rule, nothing built-in covers it. |
| `ResponseLengthEvaluator` | **Keep** (unused by the evals; harmless). |

Call-site policy: use built-in `toolCorrectness {}` where the expected call set can be pinned, adding
`expected("toolCalls", expectedToolCalls(...))` to those dataset examples; use `ToolPresenceEvaluator`
where the agent legitimately makes extra calls. Concretely, the two `toolCallEvaluator` uses in
`should not add already started sessions to preferences` become one `toolCorrectness {}` over both
expected tools, and the one in `should retrieve accurate general venue information` becomes
`toolCorrectness {}` with a single expected call.

### 4. New 0.27 talk material — `AdvancedAgentEval.kt`

A new test class, deliberately **not** appended to `ChatEval.kt`, whose method order and blank-line
gaps are the stage reveal sequence.

- **Tool calls in trajectory messages.** Rework the multi-turn test to build assistant turns with
  `assistant(content, toolCalls)` so the `trajectoryEvaluator` sees tool use, not just text. Requires
  clearing and draining the recorder per turn inside the `ConversationalApplication`.
- **`GoldenGenerator` / `ScenarioSeed`.** Generate multi-turn conversation goldens from scenario seeds
  instead of hand-writing them. Pairs naturally with the existing simulator test and with the
  `generate-goldens` skill in `docs/dokimos/agent-skills/`.
- **`PlanQuality` / `PlanAdherence`.** Out of scope — see the resolved decision below.

### 5. Baselines

The Dokimos docs state that changing the evaluator set shifts the significance test's p-values and
requires a re-baseline. Every gated experiment here changes its evaluator set, so:
regenerate with `DOKIMOS_UPDATE_BASELINE=true ./mvnw test`, review the JSON, and commit it.

Also resolve the two pre-existing baseline problems found while writing `CLAUDE.md`:
`baselines/tone-evals.json` is orphaned (its experiment calls `.assert()`, not the gate), and the
gated `"rag"` baseline has never been committed, so that gate currently measures nothing.

## Resolved decision: plan evaluators are out of scope

**Decided 2026-08-31: option (b) — drop them from this round.** `PlanQuality` / `PlanAdherence` are
not part of this upgrade. Adding them remains a possible follow-up, tracked by the reasoning below.

### Background

`PlanQualityEvaluator` reads a `reasoningStepsKey`; `PlanAdherenceEvaluator` reads that plus
`toolCallsKey`. The agent currently emits neither — `/chat` returns a bare string. Options:

- **(a) Add a planning step to the agent.** Have the chat flow produce an explicit plan (structured
  output) before tool execution, surface it as `reasoningSteps`, then evaluate it. Best talk
  material — "the agent plans, and we score the plan" — but it changes application behaviour and
  needs rehearsal.
- **(b) Drop the plan evaluators** from this round and ship the other two 0.27 features.

Rationale for (b): bundling an application behaviour change into a framework upgrade makes it hard to
tell which change broke a baseline, and the plan evals are a talk segment in their own right rather
than a side effect of upgrading.

## Verification

- `./mvnw test` from `spring-ai/` with `OPENAI_API_KEY` set (evals are `@EnabledIfEnvironmentVariable`;
  a green run without the key proves nothing). Costs real tokens.
- pgvector up (`docker compose up -d`) for the RAG-backed evals; Dokimos server up for the reported ones.
- Confirm no test silently skipped, and that the regenerated baselines are reviewed before commit.

## Risks

- **Judge nondeterminism.** `application.properties` uses a floating alias (`gpt-5-chat-latest`) at
  `temperature=0.4`; the docs call out both as baseline-drift hazards. Out of scope here, but it will
  make the regenerated baselines noisier than they need to be.
- **`toolCorrectness` strictness.** If the agent makes a legitimate extra call, an F1 over name sets
  drops below 1.0 and the eval fails on stage. Where that risk is real, prefer `ToolPresenceEvaluator`.
- **Cost and runtime** of a full eval run during iteration.
