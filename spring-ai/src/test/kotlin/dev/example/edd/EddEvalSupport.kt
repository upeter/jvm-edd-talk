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
