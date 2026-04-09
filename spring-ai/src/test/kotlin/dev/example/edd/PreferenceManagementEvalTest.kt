package dev.example.edd

import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools.Companion.TOOL_ADD_PREFERRED_SESSIONS
import dev.example.ConferenceTools.Companion.TOOL_GET_PREFERRED_SESSIONS
import dev.example.ConferenceTools.Companion.TOOL_REMOVE_PREFERRED_SESSIONS
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * Evaluates the preference management agent: add, remove, and list preferred sessions.
 *
 * Quality dimensions:
 * - Tool call correctness: did the agent call the right tool?
 * - Correctness / accuracy: does the response accurately reflect the requested action?
 * - Tone & helpfulness: is the response professional and clearly confirming the action?
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class PreferenceManagementEvalTest @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val toolCallRecorder: ToolCallRecorder,
) {

    val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    @Test
    fun `should call correct tool when adding a preferred session`() {
        experiment {
            name = "Preference Management - Add Session"
            dataset {
                name = "add-preferred-sessions"
                example {
                    input = "Add the session about Kotlin error handling to my preferred sessions"
                    expected = "From Exceptions to Rich Errors: Rethinking Error Handling in Kotlin"
                    metadata("category", "add-session")
                    metadata("expectedTool", TOOL_ADD_PREFERRED_SESSIONS)
                }
                example {
                    input = "I'd like to attend the session about building Gen AI agents, please add it to my schedule"
                    expected = "Enterprise Gen AI with Embabel"
                    metadata("category", "add-session")
                    metadata("expectedTool", TOOL_ADD_PREFERRED_SESSIONS)
                }
                example {
                    input = "Can you add the Java memory forensics talk to my favorites?"
                    expected = "Catching the 137-Killer: A Java Memory Forensics Investigation"
                    metadata("category", "add-session")
                    metadata("expectedTool", TOOL_ADD_PREFERRED_SESSIONS)
                }
            }
            task { example ->
                val conversationId = UUID.randomUUID().toString()
                toolCallRecorder.clear()
                val response = controller.chat(ChatMessage(example.input(), conversationId)).orEmpty()
                val toolCalls = toolCallRecorder.getCalls().map {
                    mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
                }
                mapOf(
                    "output" to response,
                    "toolCalls" to toolCalls,
                )
            }
            evaluators {
                toolCallEvaluator {
                    expectedToolName = TOOL_ADD_PREFERRED_SESSIONS
                }
                llmJudge(judge) {
                    name = "Action Confirmation"
                    criteria = "Does the response clearly confirm that the requested session was added to the user's preferred sessions? The expected output contains the session title that should have been added — verify the response references it or an equivalent session."
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT, EvalTestCaseParam.EXPECTED_OUTPUT)
                    threshold = 0.8
                }
                llmJudge(judge) {
                    name = "Tone and Helpfulness"
                    criteria = "Is the response helpful, concise, and professionally worded? It should confirm the action clearly without unnecessary verbosity."
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
                    threshold = 0.8
                }
            }
        }.run().print()
    }

    @Test
    fun `should call correct tool when listing preferred sessions`() {
        experiment {
            name = "Preference Management - Get Sessions"
            dataset {
                name = "get-preferred-sessions"
                example {
                    input = "What sessions have I saved so far?"
                    expected = ""
                    metadata("category", "get-sessions")
                    metadata("expectedTool", TOOL_GET_PREFERRED_SESSIONS)
                }
            }
            task { example ->
                val conversationId = UUID.randomUUID().toString()
                toolCallRecorder.clear()
                val response = controller.chat(ChatMessage(example.input(), conversationId)).orEmpty()
                val toolCalls = toolCallRecorder.getCalls().map {
                    mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
                }
                mapOf(
                    "output" to response,
                    "toolCalls" to toolCalls,
                )
            }
            evaluators {
                toolCallEvaluator {
                    expectedToolName = TOOL_GET_PREFERRED_SESSIONS
                }
                llmJudge(judge) {
                    name = "Tone and Helpfulness"
                    criteria = "Is the response helpful, concise, and professionally worded? It should either list the user's preferred sessions or clearly inform the user that no sessions have been saved yet."
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
                    threshold = 0.8
                }
            }
        }.run().print()
    }

    @Test
    fun `should call correct tool when removing a preferred session`() {
        experiment {
            name = "Preference Management - Remove Session"
            dataset {
                name = "remove-preferred-sessions"
                example {
                    input = "Remove the IntelliJ IDEA session from my preferences"
                    expected = "Be more productive with IntelliJ IDEA"
                    metadata("category", "remove-session")
                    metadata("expectedTool", TOOL_REMOVE_PREFERRED_SESSIONS)
                }
            }
            task { example ->
                val conversationId = UUID.randomUUID().toString()
                toolCallRecorder.clear()
                val response = controller.chat(ChatMessage(example.input(), conversationId)).orEmpty()
                val toolCalls = toolCallRecorder.getCalls().map {
                    mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
                }
                mapOf(
                    "output" to response,
                    "toolCalls" to toolCalls,
                )
            }
            evaluators {
                toolCallEvaluator {
                    expectedToolName = TOOL_REMOVE_PREFERRED_SESSIONS
                }
                llmJudge(judge) {
                    name = "Action Confirmation"
                    criteria = "Does the response clearly confirm that the requested session was removed from the user's preferred sessions? The expected output contains the session title — verify the response references it or an equivalent session."
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT, EvalTestCaseParam.EXPECTED_OUTPUT)
                    threshold = 0.8
                }
                llmJudge(judge) {
                    name = "Tone and Helpfulness"
                    criteria = "Is the response helpful, concise, and professionally worded? It should confirm the removal action clearly."
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
                    threshold = 0.8
                }
            }
        }.run().print()
    }
}
