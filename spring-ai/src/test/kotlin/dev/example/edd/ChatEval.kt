package dev.example.edd

import dev.dokimos.core.Assertions
import dev.dokimos.core.ExperimentResult
import dev.dokimos.core.JudgeLM
import dev.dokimos.core.conversation.AggregationStrategy
import dev.dokimos.core.conversation.ConversationTrajectory
import dev.dokimos.core.conversation.ConversationalApplication
import dev.dokimos.core.conversation.Message
import dev.dokimos.core.conversation.SimulatedUser
import dev.dokimos.core.conversation.TrajectoryEvaluationCriteria
import dev.dokimos.core.conversation.TrajectoryEvaluator
import dev.dokimos.kotlin.core.EvalTestCase
import dev.dokimos.kotlin.core.assertNoRegression
import dev.dokimos.kotlin.dsl.conversation.llmUser
import dev.dokimos.kotlin.dsl.conversation.simulator
import dev.dokimos.kotlin.dsl.conversation.trajectoryEvaluator
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.ChatMessage
import dev.example.ConferenceTools
import dev.example.ConferenceTools.Companion.TOOL_ADD_PREFERRED_SESSIONS
import dev.example.ConferenceTools.Companion.TOOL_CONFERENCE_SESSION_SEARCH
import dev.example.ConferenceTools.Companion.TOOL_GENERAL_SESSION_INFORMATION_JFALL
import dev.example.ConferenceTools.Companion.TOOL_GENERAL_VENUE_INFORMATION_JFALL
import dev.example.ConferenceTools.Companion.TOOL_GET_PREFERRED_SESSIONS
import dev.example.SessionPreferenceRepository
import dev.example.ToolCallRecorder
import io.kotest.assertions.AssertionErrorBuilder.Companion.fail
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ChatEval
    @Autowired
    constructor(
        val builder: ChatClient.Builder,
        val controller: AIController,
        val tools: ConferenceTools,
        val toolCallbackRecorder: ToolCallRecorder,
        val sessionPreferenceRepository: SessionPreferenceRepository,
    ) {

    @BeforeEach
        fun setup() {
        toolCallbackRecorder.clear()
    }

    @Test
        fun `should retrieve basic conference information`() {
            experiment {
                name = "J-Fall Conference Venue data Evals"
                dataset {
                    name = "first-time-attendee"
                    example {
                        input = "Where is J-Fall 2026 held?"
                        expected = "Laan der Verenigde Naties 150, Ede"
                    }
                }
                task { example ->
                    val prompt = example.input()
                    val response = controller.chat(ChatMessage(prompt, UUID.randomUUID().toString())).orEmpty()
                    mapOf("output" to response)
                }
                evaluators {
                    contains {}
                }
            }.run().print()
        }




    val judge: JudgeLM = SpringAiSupport.asJudge(builder.clone().defaultOptions(
            OpenAiChatOptions.builder().model("gpt-5.5-2026-04-23").reasoningEffort("none").temperature(0.0))
    )
        @Test
        fun `should retrieve basic conference information and evaluate tone`() {
            experiment {
                name = "J-Fall Tone Evals"
                dataset {
                    name = "first-time-attendee"
                    example {
                        input = "Harrr, I'm a pirrate developerrr talking pirate speech. " +
                            "Wherrrre is J-Fall 2026 held and what'ssss the venue location?"
                        expected = "Pathé Ede"
                    }
                }
                task { example ->
                    val prompt = example.input()
                    val response = controller.chat(ChatMessage(prompt, UUID.randomUUID().toString())).orEmpty()
                    mapOf("output" to response)
                }
                evaluators {
                    llmJudge(judge = judge) {
                        name = "Tone"
                        criteria = "Is the answer helpful, accurate and neutrally worded, using formal, literal language?"
                        threshold = 0.7
                    }
                    contains {}
                }
            }.run().print().assert()
        }







        @Test
        fun `regression should retrieve basic conference information and evaluate tone`() {
            experiment {
                name = "J-Fall Tone Evals"
                dataset {
                    name = "first-time-attendee"
                    example {
                        input = "Harrr, I'm a pirrate developerrr talking pirate speech. " +
                            "Wherrrre is J-Fall 2026 held and what'ssss the venue location?"
                        expected = "Pathé Ede"
                    }
                }
                task { example ->
                    val prompt = example.input()
                    val response = controller.chat(ChatMessage(prompt, UUID.randomUUID().toString())).orEmpty()
                    mapOf("output" to response)
                }
                evaluators {
                    llmJudge(judge = judge) {
                        name = "Tone"
                        criteria = "Is the answer helpful, accurate, neutral using formal, literal language?"
                        threshold = 0.7
                    }
                    contains {}
                }
            }.run().print().assert()
                .assertNoRegression("tone-evals") {
                    severityMargin = 0.10
                }



        }





    @Test
        fun `should retrieve accurate general venue information`() {
            experiment {
                name = "J-Fall Venue Evals"
                dataset {
                    name = "first-time-attendee"
                    example {
                        input = "Where can I park my car at the venue, and what does it cost?"
                        expected = "The venue information does not mention parking"
                        expected("toolCalls", expectedToolCalls(TOOL_GENERAL_VENUE_INFORMATION_JFALL))
                    }
                }
                task { example ->
                    val sessionId = UUID.randomUUID().toString()
                    val response = controller.chat(ChatMessage(example.input(), sessionId))!!
                    val toolCalls = toolCallbackRecorder.getCalls().asToolCalls()
                    mapOf(
                        "output" to response,
                        "context" to tools.getGeneralVenueInformation(),
                        "toolCalls" to toolCalls,
                        "toolOutput" to tools.getGeneralVenueInformation(),
                    )
                }
                evaluators {
                    toolCorrectness {}
                    faithfulness(judge) {
                        name = "Faithfulness"
                        threshold = 0.9
                        includeReason = true
                    }
                    hallucination(judge) {
                        includeReason = true
                    }
                }
            }.run().print().assert()
        }

    @Test
        fun `multiturn chat for first time attendee looking for beginner sessions`() {
            val user: SimulatedUser =
                llmUser(judge) {
                    persona = "Java backend developer who wants to add as many as possible preferred sessions to their schedule"
                    behaviorGuidelines = """
                - Is interested in sessions about backend and AI, foremost AI frameworks like LangChain4j, Spring AI and Koog.
                - Wants to fill the schedule with as many as possible sessions of his interest. 
            """
                }
            val conversationId = UUID.randomUUID().toString()
            val chatApp =
                ConversationalApplication { trajectory ->
                    val response = controller.chat(ChatMessage(trajectory.toText(), conversationId))
                    Message.assistant(response)
                }

            // Run simulation
            val trajectory: ConversationTrajectory =
                simulator {
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
            val evaluator: TrajectoryEvaluator =
                trajectoryEvaluator(judge) {
                    name = "Schedule Session Trajectory"
                    threshold = 0.7
                    criteria(
                        listOf(
                            TrajectoryEvaluationCriteria.userSatisfaction(),
                            TrajectoryEvaluationCriteria.goalCompletion(),
                            TrajectoryEvaluationCriteria.professionalTone(),
                            TrajectoryEvaluationCriteria.helpfulness(),
                        ),
                    )
                    aggregationStrategy = AggregationStrategy.WEIGHTED_MEAN
                }

            val testCase =
                EvalTestCase(
                    actualOutputs = mapOf("trajectory" to trajectory),
                )

            val result = evaluator.evaluate(testCase)

            // Print results
            println("\n=== Evaluation Results ===")
            println("Overall Score: ${"%.2f".format(result.score())}")
            println("Passed: ${result.success()}")
            println("Reason: ${result.reason()}")

            assertSoftly {
                sessionPreferenceRepository
                    .getPreferredSessionsBy(conversationId)
                    .shouldNotBeEmpty()
                    .groupBy { it.startsAt }
                    .forEach { (date, sessions) ->
                        withClue(
                            "Multiple Sessions on slot $date:\n- ${sessions.joinToString("\n- ") { it.title }}",
                        ) { sessions.shouldHaveSize(1) }
                    }
            }
        }

    //must fail due to sessions being proposed in the past
    @Test
        fun `should not add already started sessions to preferences`() {
            experiment {
                name = "J-Fall Started Session Preference Evals"
                dataset {
                    name = "schedule-after-sessions-started"
                    example {
                        input =
                            """
                            It is Thursday November 12, 2026 at 15:00.
                            I am interested in Spring AI and AI agent sessions for my preferences that I can attend.
                            Add suitable sessions to my preferred schedule.
                            """.trimIndent()
                        expected =
                            "Preferred sessions should not include any session that already started before " +
                                "Thursday November 12, 2026 at 15:00 conference-local time."
                        metadata("currentTime", conferenceLocalTime("2026-11-12T15:00"))
                        expected("toolCalls", expectedToolCalls(TOOL_ADD_PREFERRED_SESSIONS, TOOL_CONFERENCE_SESSION_SEARCH))
                    }
                }
                task { example ->
                    val conversationId = UUID.randomUUID().toString()
                    val response = controller.chat(ChatMessage(example.input(), conversationId)).orEmpty()
                    val preferredSessions = sessionPreferenceRepository.getPreferredSessionsBy(conversationId)
                    val toolCalls = toolCallbackRecorder.getCalls().asToolCalls()

                    mapOf(
                        "output" to response,
                        "toolCalls" to toolCalls,
                        "preferredSessions" to preferredSessions,
                        "currentTime" to example.metadata().getValue("currentTime"),

                    )
                }
                evaluators {
                    toolCorrectness {}
                    startedSessionOverlap {}
                }
            }.run().print().assert()
        }
    }
