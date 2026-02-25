# Sources for Eval Driven Development on the JVM talk

Conference talk repo demonstrating **Eval-Driven Development (EDD)** on the JVM using the **Dokimos** framework.

- Dokimos overview: https://dokimos.dev/overview
- Main idea: treat evals as first-class tests, run them locally, and inspect results in the Dokimos statistics dashboard.

---

## Modules Overview

### 1) `spring-ai/` (Spring Boot + Spring AI + Dokimos evals)
A Kotlin/Spring Boot app that:

- Exposes chat endpoints (text + audio) backed by OpenAI via Spring AI
- Uses **pgvector** for retrieval (conference session data)
- Emits traces to **Langfuse** via OpenTelemetry (optional but recommended)
- Contains **Dokimos eval tests** under `spring-ai/src/test/kotlin/...` (see e.g. `dev.example.edd.ChatEval`)

Key configuration lives in `spring-ai/src/main/resources/application.properties` (port **8082**, pgvector on **localhost:5430**, etc.).

### 2) `chatclient-kmp/` (Kotlin Multiplatform desktop client)
A Compose Desktop client (JVM target) that talks to the `spring-ai` server.

- Text chat UI
- Audio chat UI (records/uploads audio, plays back TTS)

### 3) `docker-compose.yaml` (pgvector)
A small Docker Compose setup that starts Postgres + pgvector on **localhost:5430** and initializes the schema/data via `docker-entrypoint-initdb.d/`.

### 4) Dokimos Eval statistics server (external)
Dokimos provides a small server + dashboard to collect and visualize eval results.

---

## Prerequisites

- **Java 21** (both modules target JVM 21)
- Docker (for pgvector + Dokimos statistics server)
- An OpenAI API key
- (Optional but recommended) a free Langfuse Cloud account

---

## Environment variables (required for `spring-ai/`)

Set these in your shell before running the Spring Boot app or the eval tests.

```bash
export OPENAI_API_KEY=<open-ai-key>

# Langfuse (requires a free account on https://cloud.langfuse.com/)
export LANGFUSE_BASE_URL=https://cloud.langfuse.com
export LANGFUSE_PUBLIC_KEY=<pk>
export LANGFUSE_SECRET_KEY=<sk>

# OpenTelemetry export to Langfuse
export OTEL_EXPORTER_OTLP_ENDPOINT=https://cloud.langfuse.com/api/public/otel/
export OTEL_EXPORTER_OTLP_HEADERS='Authorization=Basic <pk:sk base64>'
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://cloud.langfuse.com/api/public/otel/v1/traces
```

Notes:
- `OPENAI_API_KEY` is mandatory because `spring-ai/src/main/resources/application.properties` references it.
- To build the Basic header value, base64-encode the literal string `"<pk>:<sk>"`.

---

## Running the infrastructure

### 1) Start pgvector (for RAG)
From the repo root:

```bash
docker compose up -d
```

This uses `./docker-compose.yaml` and exposes Postgres on `localhost:5430`.

### 2) Start Dokimos Eval statistics server (dashboard)
In a separate folder (or anywhere), run:

```bash
curl -O https://raw.githubusercontent.com/dokimos-dev/dokimos/master/docker-compose.yml
docker compose up -d
```

Open http://localhost:8080 to view the dashboard.

---

## Running `spring-ai/`

### Start the Spring Boot app
From `spring-ai/`:

```bash
./mvnw spring-boot:run
```

The app starts on:
- http://localhost:8082

### Useful endpoints
The controller is `dev.example.AIController`.

- `POST /chat` (JSON) – text chat
- `POST /audio-chat` (multipart) – audio in, audio out (TTS)
- `POST /audio-in-text-out-chat` (multipart) – audio in, text out
- `POST /feedback` (JSON) – user feedback
---

## Running the Dokimos evals

The evals live under `spring-ai/src/test/kotlin/`.

A representative suite is `dev.example.edd.ChatEval`:
- Uses Dokimos `experiment { ... }` DSL
- Optionally reports results to the Dokimos statistics server at `http://localhost:8080`

Run tests from `spring-ai/`:

```bash
./mvnw test
```

Notes:
- Some tests are guarded with `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", ...)`.
- For dashboard reporting, make sure the Dokimos statistics server is running (see above).

---

## Running `chatclient-kmp/` (desktop client)

From `chatclient-kmp/`:

```bash
# This module currently only ships `gradlew.bat` (Windows).
# On macOS/Linux, use your local Gradle installation.
gradle run
```

The UI expects the `spring-ai` server to be running on `http://localhost:8082`.

---
