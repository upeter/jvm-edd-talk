package dev.example.edd

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dokimos.core.JudgeLM
import dev.dokimos.kotlin.dsl.experiment
import dev.dokimos.springai.SpringAiSupport
import dev.example.AIController
import dev.example.ChatMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class SessionRecommendationEvalTest @Autowired constructor(
    private val controller: AIController,
    builder: ChatClient.Builder,
) {

    private val judge: JudgeLM = SpringAiSupport.asJudge(builder)

    @Test
    fun `should recommend relevant conference sessions for attendee personas`() {
        val dataset = loadDataset("/datasets/session-recommendation-eval.json")

        experiment {
            name = "JFall Session Recommendation Eval"
            dataset {
                name = dataset.name
                dataset.examples.forEach { item ->
                    example {
                        input = item.input
                        expected = item.expectedOutput
                        item.metadata.forEach { (key, value) -> metadata(key, value) }
                    }
                }
            }
            task { example ->
                val response = controller.chat(
                    ChatMessage(
                        message = example.input(),
                        conversationId = UUID.randomUUID().toString(),
                    )
                ).orEmpty()
                mapOf("output" to response)
            }
            evaluators {
                llmJudge(judge) {
                    name = "Session Recommendation Quality"
                    criteria =
                        "Is the answer helpful and relevant to the user request, grounded in the conference context, and clear about uncertainty when exact matches are not available?"
                    threshold = 0.8
                }
            }
        }.run().print()
    }

    private fun loadDataset(path: String): RecommendationDataset {
        val mapper = jacksonObjectMapper()
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) {
            "Dataset not found on classpath: $path"
        }
        stream.use { return mapper.readValue(it) }
    }
}

private data class RecommendationDataset(
    val name: String,
    val examples: List<RecommendationExample>,
)

private data class RecommendationExample(
    val input: String,
    val expectedOutput: String,
    val metadata: Map<String, String> = emptyMap(),
)
