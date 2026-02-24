package dev.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap


data class ConferenceSessionSearchResult(
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val room: String,
    val speakers: List<String>,
    val score: Double
)

@Repository
class SessionSearchRepository(val vectorStore: VectorStore) {

    fun searchSessions(query: String): List<ConferenceSessionSearchResult> {
        val searchRequest = SearchRequest.builder()
            .query(query)
            .similarityThreshold(0.3)
            .topK(10).build().also { logger.info("Query: ${query}") }
        return vectorStore.similaritySearch(searchRequest)
            .groupBy { it.metadata.getValue("title") }
            .map { (title, documents) ->
                documents.maxBy { it.score ?: 0.0 }.let {
                    ConferenceSessionSearchResult(
                        title = title.toString(),
                        startsAt = it.metadata.getValue("startsAtLocalDateTime").toString(),
                        endsAt = it.metadata.getValue("endsAtLocalDateTime").toString(),
                        room = it.metadata.getValue("room").toString(),
                        speakers = it.metadata.getValue("speakers").toString().split(",").toList(),
                        score = it.score ?: 0.0
                    )
                }
            }
    }
}


    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ConferenceSession(
        val title: String,
        val startsAt: String,
        val endsAt: String,
        val category: List<String>,
        val speakers: List<String>,
        val room: String
    )


    @Repository
    class SessionPreferenceRepository() {

        fun getPreferredSessionsBy(conversationKey: String): Set<ConferenceSession> =
            preferences[conversationKey]?.toSet().orEmpty()

        fun addToPreferenceSessions(conversationKey: String, sessionTitle: String) {
            requireNotNull(findBySessionTitle(sessionTitle)).let { session ->
                updatePreferences(conversationKey) {
                    it.add(session)
                }
            }
        }

        fun removePreferredSession(conversationKey: String, sessionTitle: String) {
            requireNotNull(findBySessionTitle(sessionTitle)).let { session ->
                updatePreferences(conversationKey) {
                    it.remove(session)
                }
            }
        }

        companion object {
            private val mapper: ObjectMapper = jacksonObjectMapper()

            private val sessions: List<ConferenceSession> = run {
                val dataset: Dataset = mapper.readValue(
                    SessionPreferenceRepository::class.java
                        .getResourceAsStream("/data/dataset-jfall-sessions.json")
                )
                dataset.sessions
            }

            fun findBySessionTitle(sessionTitle: String): ConferenceSession? = sessions.firstOrNull {
                it.title.lowercase().startsWith(sessionTitle.lowercase())
            }


            fun updatePreferences(
                conversationId: String,
                transform: (PersistentSet<ConferenceSession>) -> PersistentSet<ConferenceSession>
            ) {
                preferences.compute(conversationId) { _, existing ->
                    transform((existing ?: persistentSetOf()))
                }
            }

            private val preferences = ConcurrentHashMap<String, PersistentSet<ConferenceSession>>()


        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Dataset(val sessions: List<ConferenceSession> = emptyList())






