package dev.example.edd

import dev.dokimos.core.JudgeLM
import dev.dokimos.core.MatchingStrategy
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.server.client.DokimosServerReporter
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools
import dev.example.ConferenceTools.Companion.TOOL_CONFERENCE_SESSION_SEARCH
import dev.example.ConferenceTools.Companion.TOOL_GENERAL_VENUE_INFORMATION_JFALL
import dev.example.ToolCallRecorder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ChatEval @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val tools: ConferenceTools,
    val toolCallbackRecorder: ToolCallRecorder,
) {
    val serverReporter = DokimosServerReporter.builder()
        .serverUrl("http://localhost:8080")
        .projectName("jfall-chat-app-evals")
        .build()

    val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    @Test
    fun `should retrieve general conference information`() {
        experiment {
            name = "JFall Conference Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "What's the name of this conference?"
                    expected = "JFall 2025"
                }
            }
            task { example ->
                val prompt = example.input()
                val response = controller.chat(ChatMessage(prompt, UUID.randomUUID().toString())).orEmpty()
                mapOf(
                    "output" to response,
                )

            }
            evaluators {
                exactMatch {
                    name = "Exact Match"
                    threshold = 1.0
                }

            }
        }.run().print()
    }


    @Test
    fun `should retrieve general venue information`() {
        experiment {
            name = "JFall Venue Evals"
            dataset {
                name = "first-time-attendee"
//                example {
//                    input = "What’s the address of the JFall 2025 venue?"
//                    expected = "Laan der Verenigde Naties 150, 6716 JE Ede"
//                    metadata("userType", "firstTimeAttendee")
//                    metadata("complexity", "small")
//                }
//                example {
//                    input = "On what date is JFall 2025 held?"
//                    expected = "November 6, 2025"
//                    metadata("userType", "firstTimeAttendee")
//                    metadata("complexity", "small")
//                }
                example {
                    input = " What is the regular ticket price for JFall 2025?"
                    expected = "€95"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "medium")
                }
            }

            task { example ->
                val sessionId = "1212121212"
                val prompt = example.input()
                val response = controller.chat(ChatMessage(prompt, sessionId))!!

                val toolCalls = toolCallbackRecorder.getCalls().map {
                    mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
                }
                mapOf(
                    "output" to response,
                    "retrievedContext" to  tools.getGeneralVenueInformation(),
                    "toolCalls" to toolCalls,
                    "toolOutput" to tools.getGeneralVenueInformation()
                ) + example.expectedOutputs()
            }

            evaluators {
                toolCallEvaluator {
                    expectedToolName = TOOL_GENERAL_VENUE_INFORMATION_JFALL
                    toolOutputKey = "toolOutput"
                }
                //exactMatch { threshold = 0.5 }
//                llmJudge(judge) {
//                    name = "Answer Quality"
//                    criteria = "Is the answer helpful, accurate, and professionally worded?"
//                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
//                    threshold = 0.8
//                }
//
//                faithfulness(judge) {
//                    name = "Faithfulness"
//                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
//                    threshold = 0.9
//                    contextKey = "retrievedContext"
//                    includeReason = true
//                }
                contextualRelevance(judge) {
//                    threshold = 0.9
                    retrievalContextKey = "retrievedContext"
                    includeReason = true
                    strictMode = true  // Set to true for threshold of 1.0
                }
            }
            reporter = serverReporter
        }.run().print()


    }

    @Test
    fun `should retrieve session information`() {
        experiment {
            name = "JFall Session Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "Search for sessions about Spring AI, LLMs, or agentic applications."
                    input("searchQuery", "Spring AI, LLM, agentic application")
                    //expected = "Laan der Verenigde Naties 150, 6716 JE Ede"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")
                }
            }
            task { example ->
                val sessionId = "1212121212"
                val prompt = example.input()
                val response = controller.chat(ChatMessage(prompt, sessionId))!!

                val toolCalls = toolCallbackRecorder.getCalls().map {
                    mapOf("toolName" to it.toolName, "toolInput" to it.inputJson, "toolOutput" to it.output)
                }
                val searchQuery = example.inputs().getValue("searchQuery").toString()
                val expectedSearchResult = tools.searchSessions(searchQuery)
                mapOf(
                    "output" to response,
                    "retrievedContext" to expectedSearchResult,
                    "toolCalls" to toolCalls,
                    "toolInput" to searchQuery,
                    "toolOutput" to expectedSearchResult,
                )
            }
            evaluators {
                toolCallEvaluator {
                    expectedToolName = TOOL_CONFERENCE_SESSION_SEARCH
                    toolInputKey = "toolInput"
                    toolOutputKey = "toolOutput"
                }
//                faithfulness(judge) {
//                    threshold = 0.6
//                    contextKey = "retrievedContext"
//                    includeReason = true
//                }
//                //not working well because it compares the user query to the context,
//                //which for titles is not always easily relatable
//                contextualRelevance(judge) {
//                    threshold = 0.9
//                    retrievalContextKey = "retrievedContext"
//                    includeReason = true
//                    //strictMode = true  // Set to true for threshold of 1.0
//                }
//                hallucination(judge) {
//                    threshold = 0.2  // Allow at most 20% hallucinated content
//                    contextKey = "retrievedContext"
//                    includeReason = true
//                }
                precision {
                    name = "retrieval-precision"
                    retrievedKey = "retrievedDocs"   // Key in actualOutputs
                    expectedKey = "retrievedContext"     // Key in expectedOutputs (ground truth)
                    matchingStrategy = MatchingStrategy.byEquality()
                    threshold = 0.8
                }
            }
        }.run().print()
    }
}


//["[{
//"verdict": "Yes",
//"reasoning": "The claim states that the date is November 6, 2025, which matches the truth that the event takes place on November 6, 2025."}]"]
//["Compare each CLAIM against the reference TRUTHS.
//
//TRUTHS: [The event takes place on November 6, 2025.]
//CLAIMS: [The date is November 6, 2025.]
