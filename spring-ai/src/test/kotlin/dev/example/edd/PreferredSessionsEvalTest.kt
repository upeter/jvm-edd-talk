package dev.example.edd

import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools.Companion.TOOL_ADD_PREFERRED_SESSIONS
import dev.example.ConferenceTools.Companion.TOOL_GET_PREFERRED_SESSIONS
import dev.example.ConferenceTools.Companion.TOOL_REMOVE_PREFERRED_SESSIONS
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class PreferredSessionsEvalTest @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val toolCallbackRecorder: ToolCallRecorder,
) {

    private val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    @BeforeEach
    fun clearRecorder() {
        toolCallbackRecorder.clear()
    }

    // ---------------------------------------------------------------------------
    // Helper: run a single-turn chat and return (response, captured tool calls)
    // ---------------------------------------------------------------------------
    private fun chat(message: String, conversationId: String): Pair<String, List<Map<String, Any>>> {
        val response = controller.chat(ChatMessage(message, conversationId)).orEmpty()
        val toolCalls = toolCallbackRecorder.getCalls().map {
            mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
        }
        return response to toolCalls
    }

    // ---------------------------------------------------------------------------
    // 1. Add a session to preferences
    // ---------------------------------------------------------------------------
    @Test
    fun `should add a conference session to preferences when asked`() {
        experiment {
            name = "JFall Preferred Sessions – Add"
            dataset {
                name = "add-preferred-session"
                example {
                    input = "Please add the session 'Bootiful Spring AI' to my preferred sessions."
                    expected = "Bootiful Spring AI"
                    metadata("operation", "add")
                    metadata("complexity", "simple")
                }
                example {
                    input = "I'd like to save 'Kotlin Coroutines in Practice' to my schedule."
                    expected = "Kotlin Coroutines in Practice"
                    metadata("operation", "add")
                    metadata("complexity", "simple")
                }
                example {
                    input = "Can you bookmark the talk about observability in microservices for me?"
                    expected = "observability"
                    metadata("operation", "add")
                    metadata("complexity", "medium")
                }
                // Edge case: vague / ambiguous session title
                example {
                    input = "Add the AI talk to my favorites."
                    expected = "AI"
                    metadata("operation", "add")
                    metadata("complexity", "edge")
                }
                // Negative case: session that does not exist
                example {
                    input = "Please add 'Quantum Computing with JVM' to my preferred sessions."
                    expected = "not available"
                    metadata("operation", "add")
                    metadata("complexity", "negative")
                }
            }
            task { example ->
                toolCallbackRecorder.clear()
                val conversationId = UUID.randomUUID().toString()
                val (response, toolCalls) = chat(example.input(), conversationId)
                mapOf(
                    "output"    to response,
                    "toolCalls" to toolCalls,
                )
            }
            evaluators {
                toolCallEvaluator {
                    name = "Add-Session Tool Called"
                    expectedToolName = TOOL_ADD_PREFERRED_SESSIONS
                }
                llmJudge(judge) {
                    name = "Add-Session Response Quality"
                    criteria = "Does the assistant confirm that the session was added (or explain clearly why it could not be added)? The reply should be concise, accurate, and professionally worded."
                    threshold = 0.8
                }
            }
        }.run().print()
    }

    // ---------------------------------------------------------------------------
    // 2. List preferred sessions
    // ---------------------------------------------------------------------------
    @Test
    fun `should list preferred sessions for the user`() {
        experiment {
            name = "JFall Preferred Sessions – Get"
            dataset {
                name = "get-preferred-sessions"
                example {
                    input = "What sessions have I added to my schedule?"
                    expected = "preferred sessions"
                    metadata("operation", "get")
                    metadata("complexity", "simple")
                }
                example {
                    input = "Show me my saved talks."
                    expected = "preferred sessions"
                    metadata("operation", "get")
                    metadata("complexity", "simple")
                }
                example {
                    input = "Can you remind me which conference sessions I bookmarked?"
                    expected = "preferred sessions"
                    metadata("operation", "get")
                    metadata("complexity", "medium")
                }
                // Edge case: user hasn't added anything yet (empty list)
                example {
                    input = "List my preferred sessions."
                    expected = "no sessions"
                    metadata("operation", "get")
                    metadata("complexity", "edge")
                }
                // Negative: completely off-topic request mixed in
                example {
                    input = "What is the weather like today?"
                    expected = "not about preferred sessions"
                    metadata("operation", "irrelevant")
                    metadata("complexity", "negative")
                }
            }
            task { example ->
                toolCallbackRecorder.clear()
                val conversationId = UUID.randomUUID().toString()
                val (response, toolCalls) = chat(example.input(), conversationId)
                mapOf(
                    "output"    to response,
                    "toolCalls" to toolCalls,
                )
            }
            evaluators {
                toolCallEvaluator {
                    name = "Get-Sessions Tool Called"
                    expectedToolName = TOOL_GET_PREFERRED_SESSIONS
                }
                llmJudge(judge) {
                    name = "Get-Sessions Response Quality"
                    criteria = "Does the assistant accurately report the user's preferred sessions (or correctly indicate that none have been saved yet)? The reply must be helpful and clear."
                    threshold = 0.8
                }
            }
        }.run().print()
    }

    // ---------------------------------------------------------------------------
    // 3. Remove a session from preferences
    // ---------------------------------------------------------------------------
    @Test
    fun `should remove a session from preferences when asked`() {
        experiment {
            name = "JFall Preferred Sessions – Remove"
            dataset {
                name = "remove-preferred-session"
                example {
                    input = "Please remove 'Bootiful Spring AI' from my preferred sessions."
                    expected = "Bootiful Spring AI"
                    metadata("operation", "remove")
                    metadata("complexity", "simple")
                }
                example {
                    input = "I changed my mind – take the Kotlin Coroutines talk off my list."
                    expected = "Kotlin Coroutines"
                    metadata("operation", "remove")
                    metadata("complexity", "simple")
                }
                example {
                    input = "Drop the microservices observability session from my schedule."
                    expected = "observability"
                    metadata("operation", "remove")
                    metadata("complexity", "medium")
                }
                // Edge case: remove a session that was never added
                example {
                    input = "Remove 'Introduction to Rust' from my preferences."
                    expected = "not found"
                    metadata("operation", "remove")
                    metadata("complexity", "edge")
                }
                // Negative: very long input with multiple requests — only one is a remove
                example {
                    input = "I have been thinking all day and after carefully considering all the options I would like to remove the AI talk from my preferred sessions and also maybe look at the schedule overview."
                    expected = "AI"
                    metadata("operation", "remove")
                    metadata("complexity", "negative")
                }
            }
            task { example ->
                toolCallbackRecorder.clear()
                val conversationId = UUID.randomUUID().toString()
                val (response, toolCalls) = chat(example.input(), conversationId)
                mapOf(
                    "output"    to response,
                    "toolCalls" to toolCalls,
                )
            }
            evaluators {
                toolCallEvaluator {
                    name = "Remove-Session Tool Called"
                    expectedToolName = TOOL_REMOVE_PREFERRED_SESSIONS
                }
                llmJudge(judge) {
                    name = "Remove-Session Response Quality"
                    criteria = "Does the assistant confirm the removal (or explain clearly why the session could not be removed)? The reply should be concise and accurate."
                    threshold = 0.8
                }
            }
        }.run().print()
    }
}
