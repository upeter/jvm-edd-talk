package dev.example.edd

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.dokimos.core.Dataset
import dev.dokimos.core.ExperimentResult
import dev.dokimos.kotlin.core.assertNoRegression
import dev.dokimos.kotlin.dsl.experiment
import dev.example.ChunkingStrategy
import dev.example.SessionDocuments
import dev.example.SessionIngestion
import dev.example.SessionRecord
import dev.example.SessionSearchRepository
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Path
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Retrieval evals for the session search: does `searchSessions` rank the right talk near the top?
 *
 * Runs the same query set against every [ChunkingStrategy] — each lives in its own pgvector table,
 * ingested on first use — so chunking decisions are measured, not guessed. Scoring is deterministic
 * (rank-based, no LLM judge); the only model involved is the embedding model.
 *
 * The query set ([DATASET_PATH]) holds one generated query per session plus a hand-written slice,
 * each tagged with a [QueryType] so results can be sliced by the kind of question asked.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RAGEval
    @Autowired
    constructor(
        val sessionIngestion: SessionIngestion,
        val chatClientBuilder: ChatClient.Builder,
    ) {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ChunkingStrategy::class)
        fun `retrieval quality per chunking strategy`(strategy: ChunkingStrategy) {
            retrievalExperiment(strategy).run()
                .also { printMisses(strategy, it) }
                // Hit@k flips 0↔1 on a single near-tie, so the per-item severity guard would flake on a
                // dataset this size. Leave regressions to the significance test (McNemar / permutation).
                .assertNoRegression("rag-${strategy.name.lowercase().replace('_', '-')}") {
                    severityMargin = 1.0
                }
        }

        @Test
        fun `compare chunking strategies`() {
            val results = ChunkingStrategy.entries.associateWith { retrievalExperiment(it).run() }
            printComparison(results)
        }

        /**
         * Regenerates [DATASET_PATH]. Disabled by default: it calls the chat model and rewrites a
         * reviewed artifact. Remove @Disabled, run it once, review the diff, commit.
         */
        @Test
        @Disabled("Run manually to regenerate src/test/resources/datasets/rag-retrieval.json")
        fun `generate retrieval queries`() {
            val chatClient = chatClientBuilder.build()
            val sessions = SessionDocuments.load().filter { it.description.length >= MIN_DESCRIPTION_CHARS }
            val generated = runBlocking(Dispatchers.IO.limitedParallelism(8)) {
                sessions.mapIndexed { index, session ->
                    val type = QueryType.forIndex(index)
                    async {
                        val query = chatClient.prompt().user(queryPrompt(session, type)).call().content()
                            .orEmpty().trim().trim('"')
                        RetrievalCase(query, session.title, type)
                    }
                }.awaitAll()
            }
            writeDataset(CURATED_CASES + generated)
        }

        private fun retrievalExperiment(strategy: ChunkingStrategy) = experiment {
            val repository = searchRepositoryFor(strategy)
            name = "RAG Retrieval - $strategy"
            dataset(Dataset.fromJson(DATASET_PATH))
            parallelism = 8
            task { example ->
                val titles = repository.searchSessions(example.input()).map { it.title }
                mapOf(
                    "output" to titles.take(3).joinToString(" | "),
                    RetrievalRankEvaluator.PARAM_RETRIEVED to titles,
                )
            }
            evaluators {
                retrievalRank { name = "Hit@1"; k = 1 }
                retrievalRank { name = "Hit@5"; k = 5 }
                retrievalRank { name = "MRR" }
            }
        }

        private fun searchRepositoryFor(strategy: ChunkingStrategy): SessionSearchRepository {
            sessionIngestion.ingestIfEmpty(strategy)
            return SessionSearchRepository(sessionIngestion.vectorStoreFor(strategy))
        }

        private fun printMisses(strategy: ChunkingStrategy, result: ExperimentResult) {
            val misses = result.itemResults().filter { it.score("Hit@5") == 0.0 }
            println("=".repeat(60))
            println("$strategy — Hit@5 misses: ${misses.size} of ${result.itemResults().size}")
            println("=".repeat(60))
            misses.forEach { println("  [${it.type()}] \"${it.example().input()}\" → ${it.example().expectedOutput()}") }
        }

        private fun printComparison(results: Map<ChunkingStrategy, ExperimentResult>) {
            val metrics = listOf("Hit@1", "Hit@5", "MRR")
            val items = results.values.first().itemResults()
            println("=".repeat(72))
            println("Chunking strategy comparison (${items.size} queries)")
            println("=".repeat(72))
            println("%-18s %6s".format("Strategy", "Docs") + metrics.joinToString("") { "%8s".format(it) })
            results.forEach { (strategy, result) ->
                val docs = sessionIngestion.documentCount(strategy.tableName) ?: 0
                println("%-18s %6d".format(strategy, docs) + metrics.joinToString("") { "%8.2f".format(result.averageScore(it)) })
            }

            val types = items.map { it.type() }.distinct().sorted()
            println()
            println("MRR by query type")
            println("%-18s".format("Strategy") + types.joinToString("") { t -> "%14s".format("$t(${items.count { it.type() == t }})") })
            results.forEach { (strategy, result) ->
                val byType = result.itemResults().groupBy { it.type() }
                println("%-18s".format(strategy) + types.joinToString("") { t ->
                    "%14.2f".format(byType[t].orEmpty().map { it.score("MRR") }.average())
                })
            }

            println()
            println("Paired MRR difference (row − column), 95% bootstrap CI, per-query wins/losses")
            val strategies = results.keys.toList()
            strategies.forEachIndexed { i, a ->
                strategies.drop(i + 1).forEach { b ->
                    val (scoresA, scoresB) = pairedScores(results.getValue(a), results.getValue(b), "MRR")
                    val diffs = scoresA.zip(scoresB) { x, y -> x - y }
                    val (low, high) = bootstrapMeanCi(diffs)
                    val verdict = when {
                        low > 0 -> "$a better"
                        high < 0 -> "$b better"
                        else -> "no significant difference"
                    }
                    println(
                        "  %-16s vs %-16s Δ=%+.3f [%+.3f, %+.3f]  wins %3d / losses %3d / ties %3d  → %s".format(
                            a, b, diffs.average(), low, high,
                            diffs.count { it > 0 }, diffs.count { it < 0 }, diffs.count { it == 0.0 }, verdict,
                        ),
                    )
                }
            }
            println("=".repeat(72))
        }

        private fun pairedScores(a: ExperimentResult, b: ExperimentResult, metric: String): Pair<List<Double>, List<Double>> {
            val byKeyB = b.itemResults().associateBy { it.key() }
            val pairs = a.itemResults().mapNotNull { item -> byKeyB[item.key()]?.let { item.score(metric) to it.score(metric) } }
            return pairs.map { it.first } to pairs.map { it.second }
        }

        /** Percentile bootstrap CI of the mean, fixed seed so the printed interval is reproducible. */
        private fun bootstrapMeanCi(values: List<Double>, resamples: Int = 10_000): Pair<Double, Double> {
            val random = Random(42)
            val means = DoubleArray(resamples) {
                var sum = 0.0
                repeat(values.size) { sum += values[random.nextInt(values.size)] }
                sum / values.size
            }.sorted()
            return means[(resamples * 0.025).toInt()] to means[(resamples * 0.975).toInt() - 1]
        }

        private fun dev.dokimos.core.ItemResult.score(metric: String): Double =
            evalResults().first { it.name() == metric }.score()

        private fun dev.dokimos.core.ItemResult.type(): String = example().metadata()["type"]?.toString() ?: "?"

        private fun dev.dokimos.core.ItemResult.key(): String = "${example().input()}→${example().expectedOutput()}"

        private fun queryPrompt(session: SessionRecord, type: QueryType): String {
            val description = session.description
            val passage = if (type == QueryType.DETAIL) description.substring(description.length / 2) else description
            return """
                |You write search queries that conference attendees type into the session search of a conference app.
                |
                |Session title: ${session.title}
                |Session description:
                |$passage
                |
                |Write exactly ONE query of this kind: ${type.instruction}
                |
                |Rules:
                |- 4 to 15 words.
                |- Do not reuse the distinctive words of the title; paraphrase instead of copying phrases.
                |  Names of tools, products, or technologies may be used as-is.
                |- The query must point to this particular session, not to any talk on the general topic.
                |
                |Respond with the query only, no quotes, no explanation.
            """.trimMargin()
        }

        private fun writeDataset(cases: List<RetrievalCase>) {
            val dataset = mapOf(
                "name" to "rag-retrieval",
                "description" to "Session-search queries with the session title they should retrieve",
                "examples" to cases.map { case ->
                    mapOf(
                        "inputs" to mapOf("input" to case.query),
                        "expectedOutputs" to mapOf("output" to case.title),
                        "metadata" to mapOf("type" to case.type.name),
                    )
                },
            )
            jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(DATASET_PATH.toFile(), dataset)
        }

        data class RetrievalCase(val query: String, val title: String, val type: QueryType)

        enum class QueryType(val instruction: String) {
            /** Hand-written; mostly details from deep inside a description. */
            CURATED("(hand-written)"),
            DETAIL(
                "a keyword-style search for one specific, concrete detail from this passage — a technique, tool, " +
                    "example, or takeaway — not the session's overall subject.",
            ),
            TOPIC("a keyword-style search for the session's main subject, paraphrased."),
            QUESTION(
                "a natural question an attendee would ask, such as \"Is there a talk that shows how to …?\" " +
                    "or \"Where can I learn about …?\"",
            );

            companion object {
                /** Deterministic mix over the generated queries: 40% DETAIL, 30% TOPIC, 30% QUESTION. */
                fun forIndex(index: Int): QueryType = when (index % 10) {
                    in 0..3 -> DETAIL
                    in 4..6 -> TOPIC
                    else -> QUESTION
                }
            }
        }

        companion object {
            val DATASET_PATH: Path = Path.of("src/test/resources/datasets/rag-retrieval.json")

            /** Sessions like "Welcome to Devoxx" have too little text to write a meaningful query for. */
            const val MIN_DESCRIPTION_CHARS = 300

            private fun curated(query: String, title: String) = RetrievalCase(query, title, QueryType.CURATED)

            val CURATED_CASES = listOf(
                curated(
                    "golden datasets, rubrics and regression gates for agents",
                    "Eval Driven Development: Why you need it to make your Agentic Application survive Production",
                ),
                curated(
                    "LLM-as-a-judge in an evaluator-optimizer loop with Spring AI",
                    "Agent Loops, Decoded: Fundamentals to Agentic Patterns in Spring AI",
                ),
                curated(
                    "clean and filter data with a Java DataFrame library",
                    "Java is for Data Science, Too: Building an End-to-End ML Pipeline Without Leaving the JVM",
                ),
                curated(
                    "implementing a MicroProfile spec live on stage with an AI agent",
                    "Can AI Build a Jakarta EE Runtime? The Vidocq Experiment",
                ),
                curated(
                    "pass sensitive information between modules without method parameters",
                    "Using Structured Concurrency and Scoped Values to Create a Virtual Threads Based Asynchronous Application",
                ),
                curated(
                    "indirect prompt injection and tool allowlists in LangChain4j",
                    "Your AI Agent Trusts Strangers. Let Me Show You.",
                ),
                curated(
                    "profiling Java code on the GPU with Nsight Systems",
                    "From Install to Insight: A Hands-On GPU Lab for Java Developers",
                ),
                curated(
                    "skills, hooks, git worktrees and sub-agents for agent loops",
                    "Loop Engineering, beyond prompt, context, and harness engineering",
                ),
                curated(
                    "alternate reality game website with clues hidden in a book",
                    "From Developer to Story Builder: Engineering a Novel, an ARG, and a Transmedia World with AI",
                ),
                curated(
                    "translating Scala case classes and for-comprehensions into Java records",
                    "Legacy JVM modernization with agentic AI: Quarkus vs Spring Boot",
                ),
                curated(
                    "call cuBLAS and cuFFT from Java without JNI",
                    "Tap into the NVIDIA Ecosystem from Java with TornadoVM's Hybrid API",
                ),
                curated(
                    "query files on S3 in place and build a lakehouse",
                    "From Laptop to Lakehouse: Analytical SQL with DuckDB",
                ),
                curated(
                    "EU regulation requiring human oversight of AI systems",
                    "The Importance of HITL to Avoid Chaos and EU Regulations for AI",
                ),
                curated(
                    "KV-cache compression and quantization on long-context workloads",
                    "Run Frontier Open Models on consumer hardware",
                ),
                curated(
                    "two-pizza teams, Team Topologies and T-shaped professionals",
                    "The pizza party of the future 🍕 software teams and product value in the age of coding agents",
                ),
                curated(
                    "building CLIs with picocli and terminal UIs with TamboUI",
                    "A Deserter Returns: What the Java community helped me rediscover",
                ),
                curated(
                    "containerd runtime v2 and OCI bundles to run WebAssembly on Kubernetes",
                    "Java on Kubernetes Without Container Images: The WebAssembly Way",
                ),
                curated(
                    "the software that beat the world chess champion",
                    "Software Monuments - Architectural Lessons from the Systems That Shaped Computing",
                ),
                curated(
                    "coaching skills: listening and asking the right questions",
                    "Coder, Coach, Catalyst - using questions to make people grow",
                ),
                curated(
                    "hibernate idle AI agents with gVisor on shared Kubernetes pods",
                    "Beyond the Pod: Architecting and Scaling AI Agents on Kubernetes",
                ),
                curated(
                    "find parser bottlenecks with JDK Flight Recorder",
                    "Hardwood: Building a Parquet Parser From Scratch (With a Little Help From AI)",
                ),
                curated(
                    "turn natural language requests into JPA filter objects with structured output",
                    "From User Prompt to JPA Query: Comparing Tool Calling, Structured Output & Hybrid Approach",
                ),
                curated("a colleague on my team is a bully", "Help! I hate my team mate!"),
                curated(
                    "sovereignty and self-hosting models versus hosted APIs",
                    "Owning the inference layer: When and how to run your own models",
                ),
                curated("Project Leyden with Quarkus", "Quarkus Meets Leyden at the JVM Performance Edge"),
            )
        }
    }
