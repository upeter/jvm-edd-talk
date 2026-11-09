package dev.example.edd.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Classifies eval experiments by stability, from the run history kept by the Dokimos server.
 *
 * A JUnit test either passes or fails, so an eval may only block a build if it is green almost
 * every time. Nobody can tell that from one run. The Dokimos server keeps every run, which makes it
 * the record to read: for each experiment this reports how often a run was *fully green* (every
 * item passed, which is what `.assert()` needs), the current streak of fully green runs, and a
 * suggested stage (see `EvalTier`):
 *
 *  - `block`  may also run on every PR with `.assert()` + `assertNoRegression()`: at least
 *             [Options.prStreak] consecutive fully green runs
 *  - `guard`  nightly regression gate on N-run means: enough history, not yet clean enough for the PR job
 *  - `watch`  too little history, or never fully green (known red: the agent needs fixing first)
 *
 * The tier is a suggestion for whoever sets the JUnit tag; nothing is changed anywhere. The
 * rule-of-three bound (`3 / streak`) is the reminder that even 30 clean runs only say "failure rate
 * below about 10%", not "never fails".
 *
 * Run from IntelliJ, or:
 * ```
 * ./mvnw -q test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=dev.example.edd.tools.EvalStabilityReportKt -Dexec.args="--project jfall-chat-app-evals"
 * ```
 * Server URL and API key come from `--server` / `--api-key` or `DOKIMOS_SERVER_URL` / `DOKIMOS_API_KEY`.
 */
data class Options(
    val server: String = System.getenv("DOKIMOS_SERVER_URL") ?: "http://localhost:8080",
    val apiKey: String? = System.getenv("DOKIMOS_API_KEY"),
    val project: String? = null,
    val last: Int? = null,
    val prStreak: Int = 30,
    val minHistory: Int = 5,
    val json: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var o = Options()
            val it = args.iterator()
            while (it.hasNext()) {
                when (val a = it.next()) {
                    "--server" -> o = o.copy(server = it.next())
                    "--api-key" -> o = o.copy(apiKey = it.next())
                    "--project" -> o = o.copy(project = it.next())
                    "--last" -> o = o.copy(last = it.next().toInt())
                    "--block-streak" -> o = o.copy(prStreak = it.next().toInt())
                    "--min-history" -> o = o.copy(minHistory = it.next().toInt())
                    "--json" -> o = o.copy(json = true)
                    "-h", "--help" -> {
                        println(USAGE); exitProcess(0)
                    }
                    else -> {
                        System.err.println("unknown argument: $a\n$USAGE"); exitProcess(2)
                    }
                }
            }
            return o
        }

        private val USAGE = """
            usage: EvalStabilityReport [--server URL] [--api-key KEY] [--project NAME] [--last N]
                                       [--block-streak N (30)] [--min-history N (5)] [--json]
        """.trimIndent()
    }
}

/** One recorded run, as the server reports it. */
data class RunSummary(val status: String?, val passRate: Double?, val itemCount: Int?, val startedAt: String?) {
    /** `passRate` is a double; treat anything within rounding of 1.0 as fully green. */
    val fullyGreen: Boolean get() = passRate != null && passRate >= 1.0 - 1e-9
    val successful: Boolean get() = status == "SUCCESS" && passRate != null
}

data class Stability(
    val project: String,
    val experiment: String,
    val totalRuns: Int,
    val successfulRuns: Int,
    val fullyGreenRuns: Int,
    /** Consecutive fully green runs, counted from the newest run backwards. */
    val streak: Int,
    val lastPassRate: Double?,
    val items: Int?,
) {
    val greenFraction: Double? get() = if (successfulRuns == 0) null else fullyGreenRuns.toDouble() / successfulRuns

    /** Rule of three: after n clean runs in a row, the per-run failure rate may still be about 3/n (95%). */
    val failureRateBound: Double? get() = if (streak == 0) null else minOf(1.0, 3.0 / streak)

    fun tier(prStreak: Int, minHistory: Int): String = when {
        successfulRuns < minHistory -> "watch (history < $minHistory runs)"
        fullyGreenRuns == 0 -> "watch (never fully green: known red, fix the agent)"
        streak >= prStreak -> "block"
        else -> "guard"
    }
}

/** Oldest-first; `last` keeps only the most recent runs. */
fun analyse(project: String, experiment: String, runs: List<RunSummary>, last: Int? = null): Stability {
    val ordered = runs.sortedBy { it.startedAt ?: "" }.let { if (last != null) it.takeLast(last) else it }
    val ok = ordered.filter { it.successful }
    val streak = ok.asReversed().takeWhile { it.fullyGreen }.count()
    return Stability(
        project = project,
        experiment = experiment,
        totalRuns = ordered.size,
        successfulRuns = ok.size,
        fullyGreenRuns = ok.count { it.fullyGreen },
        streak = streak,
        lastPassRate = ok.lastOrNull()?.passRate,
        items = ok.lastOrNull()?.itemCount,
    )
}

/** Read-only client for the three list endpoints the report needs. */
class DokimosReadClient(server: String, private val apiKey: String?) {
    private val base = server.trimEnd('/')
    private val http = HttpClient.newHttpClient()
    private val mapper = jacksonObjectMapper()

    fun projectNames(): List<String> = list("/api/v1/projects").map { it["name"].asText() }

    /** Pairs of experiment id and name. */
    fun experiments(project: String): List<Pair<String, String>> =
        list("/api/v1/projects/$project/experiments").map { it["id"].asText() to it["name"].asText() }

    fun runs(experimentId: String): List<RunSummary> = list("/api/v1/experiments/$experimentId/runs").map {
        RunSummary(
            status = it["status"]?.asText(),
            passRate = it["passRate"]?.takeUnless { n -> n.isNull }?.asDouble(),
            itemCount = it["itemCount"]?.takeUnless { n -> n.isNull }?.asInt(),
            startedAt = it["startedAt"]?.asText(),
        )
    }

    /** The list endpoints return a bare array today; a paged envelope is tolerated too. */
    private fun list(path: String): List<JsonNode> {
        val node = get(path)
        if (node.isArray) return node.toList()
        return listOf("content", "items", "runs", "experiments", "projects")
            .firstNotNullOfOrNull { key -> node[key]?.takeIf { it.isArray } }?.toList() ?: emptyList()
    }

    private fun get(path: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(base + path)).GET()
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            System.err.println("GET $path failed: ${e.message}. Is the Dokimos server up at $base?"); exitProcess(1)
        }
        if (response.statusCode() != 200) {
            System.err.println("GET $path -> HTTP ${response.statusCode()}: ${response.body().take(200)}"); exitProcess(1)
        }
        return mapper.readTree(response.body())
    }
}

fun collect(client: DokimosReadClient, options: Options): List<Stability> {
    val projects = client.projectNames()
    val selected = options.project?.let { wanted ->
        if (wanted !in projects) {
            System.err.println("project '$wanted' not found; known: ${projects.joinToString()}"); exitProcess(2)
        }
        listOf(wanted)
    } ?: projects
    return selected.flatMap { project ->
        client.experiments(project).map { (id, name) -> analyse(project, name, client.runs(id), options.last) }
    }.sortedWith(compareBy<Stability> { it.project }.thenByDescending { it.greenFraction ?: 0.0 }.thenBy { it.experiment })
}

private fun pct(x: Double?): String = if (x == null) "-" else "%5.1f%%".format(Locale.ROOT, 100 * x)

fun render(rows: List<Stability>, options: Options): String = buildString {
    val scope = options.last?.let { "last $it runs per experiment" } ?: "all runs"
    appendLine("Dokimos server: ${options.server}   ($scope)")
    appendLine("block needs ${options.prStreak} consecutive fully green runs; fail<= is the rule-of-three bound (3/streak, 95%)")
    appendLine()
    val w = (rows.maxOfOrNull { it.experiment.length } ?: 20).coerceAtLeast(10)
    val header = "%-${w}s  %4s %4s %5s %7s %6s %7s %6s  %5s  %s".format(
        "experiment", "runs", "ok", "green", "green%", "streak", "fail<=", "last", "items", "stage",
    )
    rows.groupBy { it.project }.forEach { (project, group) ->
        appendLine("[$project]")
        appendLine(header)
        group.forEach { s ->
            appendLine(
                "%-${w}s  %4d %4d %5d %7s %6d %7s %6s  %5s  %s".format(
                    s.experiment, s.totalRuns, s.successfulRuns, s.fullyGreenRuns, pct(s.greenFraction), s.streak,
                    pct(s.failureRateBound), pct(s.lastPassRate), s.items?.toString() ?: "-",
                    s.tier(options.prStreak, options.minHistory),
                ),
            )
        }
        appendLine()
    }
    appendLine("green  = runs where every item passed (what `.assert()` needs)     ok = runs with status SUCCESS")
    append("fail<= = upper bound on the per-run failure rate implied by the clean streak; no streak, no bound")
}

fun main(args: Array<String>) {
    val options = Options.parse(args)
    val rows = collect(DokimosReadClient(options.server, options.apiKey), options)
    if (options.json) {
        val mapper = jacksonObjectMapper().writerWithDefaultPrettyPrinter()
        println(
            mapper.writeValueAsString(
                rows.map {
                    mapOf(
                        "project" to it.project, "experiment" to it.experiment, "totalRuns" to it.totalRuns,
                        "successfulRuns" to it.successfulRuns, "fullyGreenRuns" to it.fullyGreenRuns,
                        "greenFraction" to it.greenFraction, "streak" to it.streak, "failureRateBound" to it.failureRateBound,
                        "lastPassRate" to it.lastPassRate, "items" to it.items, "stage" to it.tier(options.prStreak, options.minHistory),
                    )
                },
            ),
        )
    } else {
        println(render(rows, options))
    }
}
