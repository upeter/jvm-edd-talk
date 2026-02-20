package dev.example.observability

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.observation.ObservabilityHelper
import org.springframework.stereotype.Component
import org.springframework.util.CollectionUtils
import org.springframework.util.StringUtils

@Component
class ChatModelCompletionContentObservationFilter : ObservationFilter {

    override fun map(context: Observation.Context): Observation.Context {
        if (context !is ChatModelObservationContext) return context

        val messages = context.request.instructions
        val completions = processCompletion(context)

        context.addHighCardinalityKeyValue(object : KeyValue {
            override fun getKey(): String = "gen_ai.prompt"
            override fun getValue(): String = ObservabilityHelper.concatenateStrings(messages.map { "${it.messageType}: ${it.text}" })

        })

        context.addHighCardinalityKeyValue(object : KeyValue {
            override fun getKey(): String = "gen_ai.completion"
            override fun getValue(): String = ObservabilityHelper.concatenateStrings(completions)
        })

        return context
    }


    private fun processCompletion(context: ChatModelObservationContext): List<String> {
        val response = context.response ?: return emptyList()
        return  response.results.orEmpty().mapNotNull { generation ->
            val output = generation.output ?: return@mapNotNull null
            val text = output.text
            if (StringUtils.hasText(text)) text else null
        }
    }
}
