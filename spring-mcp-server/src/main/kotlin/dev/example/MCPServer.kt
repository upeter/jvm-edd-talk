package dev.example

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema.*
import org.springaicommunity.mcp.annotation.*
import org.springframework.stereotype.Service


@Service
class ConferenceMcpServer(
    private val sessionSearchRepository: SessionSearchRepository,
    private val sessionPreferenceRepository: SessionPreferenceRepository,
) {

    // MCP Resource counterpart: expose the venue information as a retrievable blob
    @McpResource(
        name = "general-venue-information-jfall",
        description = "Returns general JFall 2026 venue information including address, dates, hotels, and the detailed session schedule in JSON form.",
        uri = "static://data/dataset-jfall-venue.json"
    )
    fun getVenueInformation(): String = venueInformation.also { logger.info("Returning venue information.") }

    // MCP Tool counterpart: similarity search for conference sessions
    @McpTool(
        name = "conference-session-search",
        description = "Performs a simple similarity search for conference sessions (title based) and returns matching results with a heuristic score."
    )
    fun searchSessions(
        exchange: McpSyncServerExchange,
        @McpProgressToken progressToken: String?,
        @McpToolParam(description = "The search query") query: String
    ): List<ConferenceSessionSearchResult> {
        exchange.loggingNotification(LoggingMessageNotification.builder()
            .level(LoggingLevel.INFO)
            .data("Start searching sessions for: $query").build())
        progressToken?.let {
            exchange.progressNotification(
                ProgressNotification(progressToken, 0.0, 1.0, "Start searching sessions for $query ")
            )
        }
        return sessionSearchRepository.searchSessions(query).also {
            exchange.loggingNotification(LoggingMessageNotification.builder()
                .level(LoggingLevel.INFO)
                .data("Found ${it.size} sessions for: $query").build())

            progressToken?.let {
                exchange.progressNotification(
                    ProgressNotification(
                        progressToken, 1.0, 1.0, "Done searching sessions for $query "
                    )
                )
            }
        }
    }

    // MCP System prompt counterpart: the JFall conference advisor prompt
    @McpPrompt(
        name = "jfall-advisor-prompt",
        description = "Returns the JFall assistant system prompt used by the chat server."
    )
    fun jfallAdvisorPrompt(): String = MCP_PROMPT.also { logger.info("Returning jfall prompt.") }



//
//
//    @McpTool(
//        name = "get-preferred-sessions",
//        description = "Get all preferred sessions of the user."
//    )
//    fun getPreferredSessionsBy(exchange: McpSyncServerExchange): Set<ConferenceSession> {
//        val id = exchange.sessionId()
//        return sessionPreferenceRepository.getPreferredSessionsBy(id)
//            .also { logger.info("Found ${it.size} preferred sessions for conversationId: $id") }
//    }
//
//    @McpTool(
//        name = "add-preferred-sessions",
//        description = "Add a session to preferences for the user"
//    )
//    fun addPreferenceSessions(
//        @McpToolParam(description = "the session title of the session to add") sessionTitle: String,
//        exchange: McpSyncServerExchange
//    ) {
//        val id = exchange.sessionId()
//        return sessionPreferenceRepository.addToPreferenceSessions(id, sessionTitle)
//            .also { logger.info("Added session: $sessionTitle to preferences for conversationId: $id") }
//    }
//
//    @McpTool(
//        name = "remove-preferred-sessions",
//        description = "Remove a session from preferences for the user."
//    )
//    fun removePreferredSession(
//        @McpToolParam(description = "the session title of the session to remove") sessionTitle: String,
//        exchange: McpSyncServerExchange
//    ) {
//        val id = exchange.sessionId()
//        return sessionPreferenceRepository.removePreferredSession(id, sessionTitle)
//            .also { logger.info("Removed session: $sessionTitle from preferences for conversationId: $id") }
//    }


    fun McpSyncServerExchange.info(message: String) {
        loggingNotification(LoggingMessageNotification.builder().level(LoggingLevel.INFO).data(message).build())
    }

    companion object {
        private val MCP_PROMPT = """
        You are a helpful and knowledgeable assistant for the JFall 2026 conference.
    
        🎯 Your objective is to help the user:
        - Discover interesting sessions
        - Manage their personal session preferences
        - Provide accurate and relevant venue information
    
        🧰 You have access to several tools. Use them wisely:
    
        • Use `conference-session-search` 
          → When the user wants to explore sessions based on a topic, speaker, or interest. 
          → Example: "Find sessions about Kotlin", "Are there talks on machine learning?"
    
        • Use `get-preferred-sessions` 
          → When the user asks to view their current preferred sessions or saved talks. 
          → Example: "What are my favorite sessions?", "Show my preferences."
    
        • Use `add-preferred-sessions` 
          → When the user wants to add a session to their personal list. 
          → The user will typically mention a session title they like.
          → Example: "Add 'Jetpack Compose in Production' to my list"
    
        • Use `remove-preferred-sessions` 
          → When the user wants to remove a session from their preferences.
          → Example: "Remove the session about coroutines"
    
        • Use `general-venue-information-jfall` 
          → When the user asks about practical or logistical details about the event, such as location, time, hotels, or schedule.
    
        🤖 Response guidelines:
        - Use tools when needed to gather up-to-date or personalized information.
        - Keep answers short, friendly, and informative.
        - Don’t fabricate answers — prefer tool or resource calls when in doubt.
    
        Always focus on providing value to the user in the context of the JFall 2026 conference.
""".trimIndent()

        val venueInformation: String =
            ConferenceMcpServer::class.java.getResourceAsStream("/data/dataset-jfall-venue.json").bufferedReader()
                .use {
                    it.readText()
                }
    }

}

//@Configuration
//class ConferenceMcpConfig(
//    private val conferenceMcpService: ConferenceMcpService,
//) {
//
//    // Expose all @McpTool methods as MCP tools
//    @Bean
//    fun conferenceToolCallbacks(): List<org.springframework.ai.tool.ToolCallback> =
//        ToolCallbacks.from(conferenceMcpService).toList()
//}
