package dev.example

import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.server.client.DokimosServerReporter
import dev.dokimos.springai.SpringAiSupport
import dev.example.ConferenceTools.Companion.TOOL_GENERAL_VENUE_INFORMATION_JFALL
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ChatEval @Autowired constructor(
    val builder:ChatClient.Builder,
    val controller:AIController,
    val tools:ConferenceTools,
    val toolCallbackRecorder: ToolCallRecorder,
) {

    @Test
    fun `should execute experiment`() {
        val serverReporter = DokimosServerReporter.builder()
            .serverUrl("http://localhost:8080")
            .projectName("jfall-chat-app-evals")
            .build()


        val judge: JudgeLM = SpringAiSupport.asJudge(builder)
        val result = experiment {
            name = "JFall Venue Evals"

            dataset {
                name = "first-time-attendee"
                example {
                    input = "What’s the address of the JFall 2025 venue?"
                    expected = "Laan der Verenigde Naties 150, 6716 JE Ede"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")

                }
                example {
                    input = "On what date is JFall 2025 held?"
                    expected = "November 6, 2025"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")
                    expected("retrievedContext", tools.getGeneralVenueInformation())
                }
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
                mapOf("output" to response,
                    "retrievedContext" to tools.getGeneralVenueInformation(),
                    "toolCalls" to toolCalls)
            }

            evaluators {
                toolCallEvaluator{
                    expectedToolName = TOOL_GENERAL_VENUE_INFORMATION_JFALL
                    expectedToolOutput = tools.getGeneralVenueInformation()
                }
                //exactMatch { threshold = 0.5 }
//                llmJudge(judge) {
//                    name = "Answer Quality"
//                    criteria = "Is the answer helpful, accurate, and professionally worded?"
//                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
//                    threshold = 0.8
//                }
//
                faithfulness(judge) {
                    name = "Faithfulness"
                    params(EvalTestCaseParam.INPUT, EvalTestCaseParam.ACTUAL_OUTPUT)
                    threshold = 0.9
                    contextKey = "retrievedContext"
                    includeReason = true
                }
//
//                contextualRelevance(judge) {
////                    threshold = 0.9
//                    retrievalContextKey = "retrievedContext"
//                    includeReason = true
//                    strictMode = true  // Set to true for threshold of 1.0
//                }
            }
            reporter = serverReporter
        }.run()

        // 6. Display results
        println("=".repeat(60))
        println("Evaluation Results")
        println("=".repeat(60))
        println("Pass rate: ${"%.0f".format(result.passRate() * 100)}%")
        println()

        println("Average Scores:")
        println("  Answer Quality: ${"%.2f".format(result.averageScore("Answer Quality"))}")
        //println("  Faithfulness: ${"%.2f".format(result.averageScore("Faithfulness"))}")
        println("  ContextualRelevance: ${"%.2f".format(result.averageScore("ContextualRelevance"))}")
        println()

        println("Detailed Results:")
        println("-".repeat(60))
        result.itemResults().forEach { item ->
            println()
            println("Question: ${item.example().input()}")
            println("Response: ${item.actualOutputs()["output"]}")
            println("Expected: ${item.example().expectedOutput()}")
            println("Status: ${if (item.success()) "✅ PASS" else "❌ FAIL"}")
            println("Scores:")
            item.evalResults().forEach { eval ->
                println("  • ${eval.name()}: ${"%.2f".format(eval.score())}${if (eval.success()) " ✅" else " ❌"}")
            }
            println("- - ".repeat(20))
        }
        println()
        println("=".repeat(60))
    }
}


//First-time attendee
//Someone who’s new to JFall (and often new to the venue) and wants to feel oriented quickly. Their focus is practical: where to go, when to arrive, how the day is structured, and “what should I attend?” They tend to ask broad questions and appreciate guided suggestions, but will still want exact facts (address, times, rooms, dates, ticket types, nearby hotels). They’re likely to use session search with plain-language queries (“beginner Kotlin”, “intro to Java performance”) and may build a small shortlist of preferred sessions once they find a few interesting ones.
//Java/Kotlin developer
//A technical attendee optimizing for content quality and relevance. They look for sessions by topic, technology, and skill level (e.g., Kotlin, Java performance, Spring, architecture, testing, LLMs, tooling, IntelliJ). Their questions are more specific (“find sessions about memory forensics”, “what talks mention Kotlin coroutines?”), and they’ll compare options and tradeoffs. They’re a heavy user of semantic session search and preference management: adding/removing sessions, refining the shortlist, and potentially planning a personal schedule around rooms/times.
//Speaker
//A presenter at the conference who cares about their own session logistics and overall conference flow. They’ll ask for venue details (address, date/time boundaries), and session schedule/room information—often phrased as “where/when am I speaking?” or “what room is my talk in?” They may also explore related talks (to avoid overlap, find similar sessions, or recommend sessions to attendees). Preference management can represent “sessions I want to attend when I’m not speaking” or “sessions I want to recommend,” but their primary intent is confidence in time/room accuracy and quick answers.
