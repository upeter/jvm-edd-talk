package dev.example.edd

import dev.dokimos.core.EvalTestCaseParam
import dev.dokimos.core.ExperimentResult
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
import dev.example.SessionPreferenceRepository
import dev.example.SessionSearchRepository
import dev.example.ToolCallRecorder
import io.kotest.assertions.AssertionErrorBuilder.Companion.fail
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import kotlinx.datetime.toLocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ToolContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ChatEval @Autowired constructor(
    val builder: ChatClient.Builder,
    val controller: AIController,
    val tools: ConferenceTools,
    val toolCallbackRecorder: ToolCallRecorder,
    val sessionPreferenceRepository: SessionPreferenceRepository
) {

    @Test
    fun `should retrieve basic conference information`() {
        experiment {
            name = "KotlinConf Conference Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "Where is KotlinConf 2026 held?"
                    expected = "Messegelände, 81823 München, Germany"
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
        }.run().print().assert()
    }


    val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    val serverReporter = DokimosServerReporter.builder()
        .serverUrl("http://localhost:8080")
        .projectName("kotlinconf-chat-app-evals")
        .build()

    @Test
    fun `should judge reply`() {
        experiment {
            name = "KotlinConf Tone Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "Where is KotlinConf 2026 held?"
                    expected = "Messegelände, 81823 München, Germany"
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
        }.run().print().assert()
    }


    @Test
    fun `should retrieve general venue information`() {
        experiment {
            name = "KotlinConf Venue Evals"
            dataset {
                name = "first-time-attendee"
                example {
                    input = "What’s the address of the KotlinConf 2026 venue?"
                    expected = "Messegelände, 81823 München, Germany"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")
                }
                example {
                    input = "On what date is KotlinConf 2026 held?"
                    expected = "May 21–22, 2026"
                    metadata("userType", "firstTimeAttendee")
                    metadata("complexity", "small")
                }
                example {
                    input = " What is the regular ticket price for KotlinConf 2026?"
                    expected = "EUR 700"
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
        }.run().print().assert()


    }



    @Test
    fun `multiturn chat for first time attendee looking for beginner sessions`() {
        val user: SimulatedUser = llmUser(judge) {
            persona = "Java/Kotlin developer who wants to add as many as possible preferred sessions to their schedule"
            behaviorGuidelines = """
                - Is interested in sessions about AI, foremost with AI frameworks like Koog, langchain4j and Spring-AI.
                - Wants to fill the schedule with as many as possible sessions of his interest.
                - Mention you are a Kotlin developer.
            """
        }
        val conversationId = UUID.randomUUID().toString()
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
                tools.getPreferredSessionsBy(ToolContext(mapOf("conversationId" to conversationId))).size >= 10
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

        assertSoftly {
            sessionPreferenceRepository.getPreferredSessionsBy(conversationId).shouldNotBeEmpty()
                .groupBy { it.startsAt }.forEach { (date, sessions) ->
                withClue("Multiple Sessions on slot $date:\n- ${sessions.joinToString("\n- ") { it.title }}") { sessions.shouldHaveSize(1) }
            }
        }
    }

    @Test
    fun `should not add already started sessions to preferences`() {
        val conversationId = UUID.randomUUID().toString()
        val currentTime = Instant.parse("2026-05-22T13:30:00Z")
        val prompt = """
            It is Friday May 22, 2026 at 13:30.
            I am interested in beginner-friendly Kotlin, KMP, and AI sessions that I can still attend.
            Add suitable sessions to my preferred schedule.
        """.trimIndent()

        val response = controller.chat(ChatMessage(prompt, conversationId)).orEmpty()
        val preferredSessions = sessionPreferenceRepository.getPreferredSessionsBy(conversationId)
        val outdatedSessions = preferredSessions.filter { session ->
            Instant.parse(session.startsAt).isBefore(currentTime)
        }

        println("=== Response ===")
        println(response)
        println("=== Preferred Sessions ===")
        preferredSessions.sortedBy { it.startsAt }.forEach { session ->
            println("${session.startsAt} - ${session.title}")
        }

        preferredSessions.shouldNotBeEmpty()
        withClue(
            "Outdated sessions were added to preferences:\n- ${
                outdatedSessions.joinToString("\n- ") { "${it.startsAt} - ${it.title}" }
            }"
        ) {
            outdatedSessions.shouldBeEmpty()
        }
    }
}

fun ExperimentResult.assert() {
    runResults.filter{it.failCount() > 0}.takeIf { it.isNotEmpty() }?.let{
        fail(it.joinToString { it.itemResults().joinToString("\n") })

    }
}
