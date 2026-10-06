package dev.example.observability

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.springframework.ai.tool.observation.ToolCallingObservationContext
import org.springframework.stereotype.Component

/** Records each tool call's arguments and result as the Langfuse input and output of its `execute_tool` observation. */
@Component
class ToolCallContentObservationFilter : ObservationFilter {

    override fun map(context: Observation.Context): Observation.Context {
        if (context !is ToolCallingObservationContext) return context

        context.addHighCardinalityKeyValue(KeyValue.of(LangfuseAttributes.OBSERVATION_INPUT, context.toolCallArguments))
        context.toolCallResult?.let {
            context.addHighCardinalityKeyValue(KeyValue.of(LangfuseAttributes.OBSERVATION_OUTPUT, it))
        }
        return context
    }
}
