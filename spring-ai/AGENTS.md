# AGENTS.md

Agent instructions for the `spring-ai/` module in this repository.

## Project overview

- Kotlin + Spring Boot 3.5 app (`java.version=21`, Kotlin 2.2) that powers a conference assistant for JFall 2026.
- Core features: text chat, audio chat (Whisper + TTS), tool calling, RAG over conference session data in pgvector.
- EDD is first-class: Dokimos evaluation tests live under `src/test/kotlin/dev/example/edd`.

## Setup commands

Run from `spring-ai/` unless noted.

- Install/build: `./mvnw -q -DskipTests compile`
- Run app: `./mvnw spring-boot:run`
- Run tests: `./mvnw test`
- Run one test class: `./mvnw -Dtest=dev.example.edd.ChatEval test`
- Start pgvector (repo root): `docker compose up -d`

## Required environment

The app and evals require OpenAI credentials; Langfuse is optional for observability and feedback evals.

- Required: `OPENAI_API_KEY`
- Optional (Langfuse):
  - `LANGFUSE_BASE_URL`
  - `LANGFUSE_PUBLIC_KEY`
  - `LANGFUSE_SECRET_KEY`
  - `OTEL_EXPORTER_OTLP_ENDPOINT`
  - `OTEL_EXPORTER_OTLP_HEADERS`
  - `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`

Runtime defaults from `src/main/resources/application.properties`:

- Server port: `8082`
- Postgres/pgvector: `jdbc:postgresql://localhost:5430/vector_store`
- DB credentials: `user` / `password`
- Chat model: `gpt-5-chat-latest`
- Embedding model: `text-embedding-3-small`

## Architecture map

- Entry point: `src/main/kotlin/dev/example/AIApp.kt`
- HTTP API: `src/main/kotlin/dev/example/AIController.kt`
  - `POST /chat`, `POST /audio-chat`, `POST /audio-in-text-out-chat`, `POST /feedback`
- Spring AI wiring: `src/main/kotlin/dev/example/AiConfig.kt`
  - `ChatClient`, chat memory advisor, OpenAI audio/image beans, startup ingestion runner
- Tool layer: `src/main/kotlin/dev/example/Tools.kt`
  - venue/session info tools, semantic search tool, preference tools
  - includes `ToolCallRecorder` + `RecordingToolCallback` for eval visibility
- Data access: `src/main/kotlin/dev/example/Repositories.kt`
  - `SessionSearchRepository` (vector search)
  - `SessionPreferenceRepository` (in-memory per-conversation preferences)
- Observability:
  - `src/main/kotlin/dev/example/observability/ChatModelCompletionContentObservationFilter.kt`
  - `src/main/kotlin/dev/example/langfuse/LangfuseFeedbackClient.kt`

## Data and retrieval

- Classpath datasets in `src/main/resources/data`.
- pgvector table configured as `public.talks`.
- If table is empty at startup, `AiConfig` ingests `dataset-jfall.json` into the vector store.
- Docker init SQL (`../docker-entrypoint-initdb.d/talks_.sql`) already contains precomputed vectors for local demos.

## Testing and eval workflow

- EDD tests are integration-style and can call real model APIs.
- Main suites:
  - `ChatEval.kt` (contains, LLM judge, tool-call checks, multi-turn simulation)
  - `FeedbackEvalTest.kt` (evaluates Langfuse feedback entries)
  - `CustomEvaluators.kt` (contains/tool-call/length evaluators)
- Some tests are gated with `@EnabledIfEnvironmentVariable` and will skip without required env vars.
- When changing prompts/tools/retrieval behavior, update or add eval cases instead of only changing implementation.

## Code conventions for this module

- Follow existing Kotlin + Spring style in this codebase:
  - constructor injection, `@Service`/`@Repository`/`@Configuration`
  - small focused data classes near usage
  - immutable collections where already used (`kotlinx.collections.immutable`)
- Keep tool names stable unless absolutely necessary; evals may assert specific tool invocations.
- Preserve API contracts for desktop client compatibility:
  - request/response DTO fields in `AIController`
  - endpoint paths and multipart field names

## Change safety checklist

Before finalizing changes, prefer running:

1. `./mvnw test`
2. If behavior changed in chat/tooling, run targeted eval class (for example `ChatEval`)
3. If retrieval changed, verify pgvector-backed queries still return expected sessions

If full tests cannot run (missing keys/services), state exactly what was not run and why.
