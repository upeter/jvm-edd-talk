package dev.example.edd

import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.conversation.AggregationStrategy
import dev.dokimos.core.conversation.ConversationalApplication
import dev.dokimos.core.conversation.Message
import dev.dokimos.core.conversation.SimulatedUser
import dev.dokimos.core.conversation.TrajectoryEvaluationCriteria
import dev.dokimos.core.conversation.TrajectoryEvaluator
import dev.dokimos.kotlin.core.EvalTestCase
import dev.dokimos.kotlin.dsl.conversation.llmUser
import dev.dokimos.kotlin.dsl.conversation.simulator
import dev.dokimos.kotlin.dsl.conversation.trajectoryEvaluator
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
import org.springframework.ai.chat.model.ToolContext
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

    @Test
    fun `should retrieve basic conference information`() {
        experiment {
            name = "JFall Conference Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "What's the name of this conference?"
                    expected = "JFall 2026"
                }
            }
            task { example ->
                val prompt = example.input()
                val response = controller.chat(ChatMessage(prompt, UUID.randomUUID().toString())).orEmpty()
                mapOf("output" to response)
            }
            evaluators {
                contains {

                }
            }
        }.run().print()
    }


    val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    val serverReporter = DokimosServerReporter.builder()
        .serverUrl("http://localhost:8080")
        .projectName("jfall-chat-app-evals")
        .build()

    @Test
    fun `should judge reply`() {
        experiment {
            name = "JFall Tone Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "Where is JFall 2026 held?"
                    expected = "Laan der Verenigde Naties 150, 6716 JE Ede"
                }
            }
            task { example ->
                val prompt = example.input()
                val response = controller.chat(ChatMessage(prompt, UUID.randomUUID().toString())).orEmpty()
                mapOf("output" to response)
            }
            evaluators {
                llmJudge(judge) {
                    name = "Tone"
                    criteria = "Is the answer helpful, accurate, and professionally worded?"
                    threshold = 0.9
                }
                contains {}

            }
            reporter = serverReporter
        }.run().print()
    }


    @Test
    fun `should retrieve general venue information`() {
        experiment {
            name = "JFall Venue Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "What’s the address of the JFall 2026 venue?"
                    expected = "Laan der Verenigde Naties 150, 6716 JE Ede"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")
                }
                example {
                    input = "On what date is JFall 2026 held?"
                    expected = "November 6, 2026"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")
                }
                example {
                    input = " What is the regular ticket price for JFall 2026?"
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
                    "retrievedContext" to tools.getGeneralVenueInformation(),
                    "toolCalls" to toolCalls,
                    "toolOutput" to tools.getGeneralVenueInformation()
                )
            }

            evaluators {
                toolCallEvaluator {
                    expectedToolName = TOOL_GENERAL_VENUE_INFORMATION_JFALL
                    toolOutputKey = "toolOutput"
                }
                faithfulness(judge) {
                    name = "Faithfulness"
                    threshold = 0.9
                    contextKey = "retrievedContext"
                    includeReason = true
                }
                contextualRelevance(judge) {
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
            }
        }.run().print()
    }


    @Test
    fun `multiturn chat for first time attendee`() {
        val user: SimulatedUser = llmUser(judge) {
            persona = "first-time-attendee user who wants to get the lowest price possible for the conference"
            behaviorGuidelines = """
                - Negotiate politely: ask whether promo codes, bundles, or fee waivers exist, and whether prices will drop later.
                - Be firm but not abusive
                - Mention you are a first-time attendee
            """
            fixedResponses(listOf("Hi"))
        }
        val chatbot: ConversationalApplication = ConversationalApplication { trajectory ->
            // Your chatbot implementation here
            val response = controller.chat(ChatMessage(trajectory.toText(), "12233445"))
            Message.assistant(response)
        }

        // Run simulation
        val trajectory = simulator {
            simulatedUser = user
            application = chatbot
            maxTurns = 6
            scenario = "User looks for "
        }
            .simulate()

        // Print conversation
        println("=== Conversation ===")
        println(trajectory.toText())

        // Evaluate
        val evaluator = trajectoryEvaluator(judge) {
            name = "Customer Service Quality"
            threshold = 0.7
            criteria(
                listOf(
                    TrajectoryEvaluationCriteria.userSatisfaction(),
                    TrajectoryEvaluationCriteria.problemResolution(),
                    TrajectoryEvaluationCriteria.professionalTone(),
                    TrajectoryEvaluationCriteria.helpfulness()
                )
            )
            aggregationStrategy = AggregationStrategy.WEIGHTED_MEAN
        }

        val testCase = EvalTestCase(
            actualOutputs = mapOf("trajectory" to trajectory)
        )

        val result = evaluator.evaluate(testCase)

        // Print results
        println("\n=== Evaluation Results ===")
        println("Overall Score: ${"%.2f".format(result.score())}")
        println("Passed: ${result.success()}")
        println("Reason: ${result.reason()}")
    }


    @Test
    fun `multiturn chat for first time attendee looking for beginner sessions`() {
        val user: SimulatedUser = llmUser(judge) {
            persona = "Java/Kotlin developer who wants to add as many as possible preferred sessions to their schedule"
            behaviorGuidelines = """
                - Is interested in sessions about AI, foremost with the Spring-AI framework.
                - Wants to fill the schedule with as many as possible sessions of his interest.
                - Mention you are a Kotlin developer.
            """
        }
        val conversationId = "12233445"
        val chatApp = ConversationalApplication { trajectory ->
            val response = controller.chat(ChatMessage(trajectory.toText(), conversationId))
            Message.assistant(response)
        }

        // Run simulation
        val trajectory = simulator {
            simulatedUser = user
            application = chatApp
            maxTurns = 6
            scenario = "User wants to complete conference schedule with preferred sessions"
            initialMessage = "Hi"
            stoppingCondition = {
                tools.getPreferredSessionsBy(ToolContext(mapOf("conversationId" to conversationId))).size >= 5
            }
        }.simulate()

        // Print conversation
        println("=== Conversation ===")
        println(trajectory.toText())

        // Evaluate
        val evaluator: TrajectoryEvaluator = trajectoryEvaluator(judge) {
            name = "Schedule Session Trajectory"
            threshold = 0.7
            criteria(
                listOf(
                    TrajectoryEvaluationCriteria.userSatisfaction(),
                    TrajectoryEvaluationCriteria.goalCompletion(),
                    TrajectoryEvaluationCriteria.professionalTone(),
                    TrajectoryEvaluationCriteria.helpfulness()
                )
            )
            aggregationStrategy = AggregationStrategy.WEIGHTED_MEAN
        }

        val testCase = EvalTestCase(
            actualOutputs = mapOf("trajectory" to trajectory)
        )

        val result = evaluator.evaluate(testCase)

        // Print results
        println("\n=== Evaluation Results ===")
        println("Overall Score: ${"%.2f".format(result.score())}")
        println("Passed: ${result.success()}")
        println("Reason: ${result.reason()}")
    }
}


//["[{
//"verdict": "Yes",
//"reasoning": "The claim states that the date is November 6, 2026, which matches the truth that the event takes place on November 6, 2026."}]"]
//["Compare each CLAIM against the reference TRUTHS.
//
//TRUTHS: [The event takes place on November 6, 2026.]
//CLAIMS: [The date is November 6, 2026.]
