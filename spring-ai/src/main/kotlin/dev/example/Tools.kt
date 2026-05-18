package dev.example

import kotlinx.collections.immutable.persistentListOf
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

@Service
class ConferenceTools(
    val sessionSearchRepository: SessionSearchRepository,
    val sessionPreferenceRepository: SessionPreferenceRepository
) {

    @Tool(
        name = TOOL_GENERAL_VENUE_INFORMATION_KOTLINCONF,
        description = "You provide general venue information about the KotlinConf 2026 conference like location, address, ticket prices, hotels, date etc."
    )
    fun getGeneralVenueInformation(): String = generalVenueInformation


    @Tool(
        name = TOOL_GENERAL_SESSION_INFORMATION_JFALL,
        description = "You provide general session information about the KotlinConf 2026 conference like title, speaker, category, room, start- and endtime "
    )
    fun getVenueInformation(): String = generalSessionInformation


    @Tool(
        name = TOOL_CONFERENCE_SESSION_SEARCH,
        description = "Performs a similarity search for conference sessions and returns matching results with score."
    )
    fun searchSessions(
        @ToolParam(description = "The search query") query: String
    ): List<ConferenceSessionSearchResult> = sessionSearchRepository.searchSessions(query)


    @Tool(
        name = TOOL_GET_PREFERRED_SESSIONS,
        description = "Get all preferred sessions of the user."
    )
    fun getPreferredSessionsBy(toolContext: ToolContext): Set<ConferenceSession> =
        sessionPreferenceRepository.getPreferredSessionsBy(toolContext.context.getValue("conversationId").toString())
            .also {
                logger.info("Found ${it.size} preferred sessions for conversationId: ${toolContext.context.getValue("conversationId")}")
            }

    @Tool(
        name = TOOL_ADD_PREFERRED_SESSIONS,
        description = "Add sessions to preferences for the user"
    )
    fun addPreferenceSessions(
        @ToolParam(description = "the session title to of the session to add") sessionTitle: String,
        toolContext: ToolContext
    ) {
        sessionPreferenceRepository.addToPreferenceSessions(
            toolContext.context.getValue("conversationId").toString(), sessionTitle
        ).also {
            logger.info(
                "Added session: $sessionTitle to preferences for conversationId: ${
                    toolContext.context.getValue("conversationId")
                }"
            )
        }
    }

    @Tool(
        name = TOOL_REMOVE_PREFERRED_SESSIONS,
        description = "Remove sessions of preferences for the user."
    )
    fun removePreferredSession(
        @ToolParam(description = "the session title of the session to remove") sessionTitle: String,
        toolContext: ToolContext
    ) {
        sessionPreferenceRepository.removePreferredSession(
            toolContext.context.getValue("conversationId").toString(),
            sessionTitle
        ).also {
            logger.info(
                "Removed session: $sessionTitle from preferences for conversationId: ${
                    toolContext.context.getValue("conversationId")
                }"
            )
        }
    }


    companion object {

        // Centralized tool names (avoid string duplication across annotations/tests)
        const val TOOL_GENERAL_VENUE_INFORMATION_KOTLINCONF = "general-venue-information-kotlinconf"
        const val TOOL_GENERAL_SESSION_INFORMATION_JFALL = "general-session-information-kotlinconf"
        const val TOOL_CONFERENCE_SESSION_SEARCH = "conference-session-search"
        const val TOOL_GET_PREFERRED_SESSIONS = "get-preferred-sessions"
        const val TOOL_ADD_PREFERRED_SESSIONS = "add-preferred-sessions"
        const val TOOL_REMOVE_PREFERRED_SESSIONS = "remove-preferred-sessions"

        val generalVenueInformation: String =
            ConferenceTools::class.java.getResourceAsStream("/data/dataset-kotlinconf-venue.json").bufferedReader()
                .use {
                    it.readText()
                }
        val generalSessionInformation: String =
            ConferenceTools::class.java.getResourceAsStream("/data/dataset-kotlinconf-sessions.json").bufferedReader()
                .use {
                    it.readText()
                }

    }


}


data class CapturedToolCall(
    val toolName: String,
    val inputJson: String,
    val output: String
)

@Component
class ToolCallRecorder {
    private val calls = AtomicReference(persistentListOf<CapturedToolCall>())
    fun clear() = calls.set(calls.get().clear())
    fun findToolCall(toolName: String): CapturedToolCall? = calls.get().find { it.toolName == toolName }
    fun recordCall(toolName: String, inputJson: String, output: String) {
        calls.getAndUpdate {
            it.add(CapturedToolCall(toolName, inputJson, output))
        }
    }
    fun getCalls(): List<CapturedToolCall> = calls.get()
}

class RecordingToolCallback(
    private val delegate: ToolCallback,
    private val recorder: ToolCallRecorder
) : ToolCallback by delegate {

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        return delegate.call(toolInput, toolContext).also {
            recorder.recordCall(
                toolName = delegate.toolDefinition.name(),
                inputJson = toolInput,
                output = it
            )
        }
    }

    override fun call(toolInput: String): String = call(toolInput, null)
}