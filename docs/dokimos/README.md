# Dokimos reference

Offline copy of the [dokimos](https://github.com/dokimos-dev/dokimos) documentation and
Claude Code eval skills, for use while building the eval-driven development talk.

- Upstream: `dokimos-dev/dokimos` @ `279c868` (2026-08-19), project version `0.28.0-SNAPSHOT`
- Version used in this repo: `dokimos.version = 0.22.0` (`spring-ai/pom.xml`) — the docs
  below track upstream `main`, so check `reference/changelog.mdx` before assuming an API exists in 0.22.0.

To refresh: re-clone upstream and re-copy `docs/docs/` → `reference/`, `plugins/*/skills/*/SKILL.md` → `agent-skills/`.

## Start here

| Doc | What it covers |
| --- | --- |
| [reference/overview.md](reference/overview.md) | What dokimos is and how the pieces fit |
| [reference/getting-started/installation.md](reference/getting-started/installation.md) | Maven/Gradle setup for Java & Kotlin |
| [reference/getting-started/evaluation.md](reference/getting-started/evaluation.md) | First evaluation, end to end |
| [reference/integrations/spring-ai.md](reference/integrations/spring-ai.md) | Spring AI integration — the one this repo uses |
| [reference/integrations/junit.md](reference/integrations/junit.md) | Running evals as JUnit tests |

## Evaluation concepts

- [datamodel.md](reference/evaluation/datamodel.md) — cases, runs, results
- [evaluators.md](reference/evaluation/evaluators.md) — built-in and custom evaluators
- [datasets.md](reference/evaluation/datasets.md) — golden datasets
- [experiments.md](reference/evaluation/experiments.md) — comparing prompt/model variants
- [agent-evaluation.md](reference/evaluation/agent-evaluation.md) — evaluating tool-using agents
- [multi-turn-conversations.md](reference/evaluation/multi-turn-conversations.md) — conversation-level evals
- [structured-typed-data.md](reference/evaluation/structured-typed-data.md) — typed outputs
- [regression-gate.md](reference/evaluation/regression-gate.md) — server-free regression gating
- [cost-and-pricing.md](reference/evaluation/cost-and-pricing.md) — token/cost accounting

## Server

[server/](reference/server/) covers the optional dokimos server: traces, curation, LLM judge,
run diffing, CI gate, alerting, auth, deployment. This repo runs it via `dokimos/docker-compose.yml`.

## Other integrations

[embabel](reference/integrations/embabel.md) · [koog](reference/integrations/koog.md) ·
[langchain4j](reference/integrations/langchain4j.md) · [openai](reference/integrations/openai.md) ·
[spring-ai-alibaba](reference/integrations/spring-ai-alibaba.md) · [mcp-server](reference/mcp-server.md)

## Tutorials

- [spring-ai-agent-evaluation.md](reference/tutorials/spring-ai-agent-evaluation.md) — build + evaluate a Spring AI agent (closest to this repo's demo)
- [evaluate-llm-output-in-junit.md](reference/tutorials/evaluate-llm-output-in-junit.md)
- [generate-conversation-test-data.md](reference/tutorials/generate-conversation-test-data.md)

## Agent skills

[agent-skills/](agent-skills/) holds the upstream Claude Code plugin SKILL.md files — the
procedures an agent follows to do eval work. See [README-plugins.md](agent-skills/README-plugins.md)
for how they are packaged as plugins.

- Authoring: [create-tests](agent-skills/create-tests.md), [create-evaluator](agent-skills/create-evaluator.md),
  [create-dataset](agent-skills/create-dataset.md), [create-experiment](agent-skills/create-experiment.md),
  [generate-goldens](agent-skills/generate-goldens.md)
- Framework-specific: [evaluate-agent](agent-skills/evaluate-agent.md),
  [evaluate-spring-ai](agent-skills/evaluate-spring-ai.md), [evaluate-koog](agent-skills/evaluate-koog.md),
  [evaluate-langchain4j](agent-skills/evaluate-langchain4j.md), [evaluate-openai](agent-skills/evaluate-openai.md),
  [evaluate-embabel](agent-skills/evaluate-embabel.md), [evaluate-spring-ai-alibaba](agent-skills/evaluate-spring-ai-alibaba.md)
