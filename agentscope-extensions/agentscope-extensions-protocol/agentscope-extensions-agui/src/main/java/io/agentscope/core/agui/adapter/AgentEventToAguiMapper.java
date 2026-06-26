/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agui.event.AguiCustomEventNames;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockEndEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates internal {@link AgentEvent}s into AG-UI protocol {@link AguiEvent}s.
 *
 * <p>One mapper instance per AG-UI run. Holds light-weight state so {@code START} / {@code END}
 * pairs are kept matched (text/reasoning messages, tool calls) and so streaming tool-result text
 * deltas can be aggregated into a single {@link AguiEvent.ToolCallResult} at the end.
 *
 * <p>Events that have no first-class AG-UI counterpart are forwarded as
 * {@link AguiEvent.Custom} with {@code name} drawn from {@link AguiCustomEventNames} and {@code
 * value} set to the original {@link AgentEvent} so AgentScope-aware clients can recover the
 * fully typed event by re-deserializing through {@code AgentEvent}'s Jackson polymorphism.
 *
 * <p>This class is not thread-safe; callers are expected to drive it from a single Reactor
 * subscription pipeline.
 */
public class AgentEventToAguiMapper {

    private final String threadId;
    private final String runId;
    private final boolean enableReasoning;
    private final boolean emitToolCallArgs;

    // Track which AGUI messages / tool calls we have opened so finishRun() can close stragglers.
    private final Set<String> openTextMessages = new LinkedHashSet<>();
    private final Set<String> openReasoningMessages = new LinkedHashSet<>();
    private final Set<String> openToolCalls = new LinkedHashSet<>();

    // Aggregate text from streaming TOOL_RESULT_TEXT_DELTA so we can emit a single
    // TOOL_CALL_RESULT at TOOL_RESULT_END (AG-UI consumers expect a complete result, not deltas).
    private final Map<String, StringBuilder> toolResultText = new LinkedHashMap<>();
    private final Map<String, String> toolResultName = new LinkedHashMap<>();

    public AgentEventToAguiMapper(
            String threadId, String runId, boolean enableReasoning, boolean emitToolCallArgs) {
        this.threadId = threadId;
        this.runId = runId;
        this.enableReasoning = enableReasoning;
        this.emitToolCallArgs = emitToolCallArgs;
    }

    /**
     * Map a single {@link AgentEvent} into zero or more {@link AguiEvent}s.
     *
     * <p>Returns an empty list when an event has no observable AG-UI projection (e.g.
     * {@link AgentStartEvent} — the adapter emits {@code RUN_STARTED} itself).
     */
    public List<AguiEvent> map(AgentEvent event) {
        List<AguiEvent> out = new ArrayList<>();

        // Lifecycle (AGENT_START is absorbed by the adapter; AGENT_RESULT becomes a
        // MESSAGES_SNAPSHOT for downstream UIs; AGENT_END maps to RUN_FINISHED).
        if (event instanceof AgentStartEvent) {
            return out;
        }
        if (event instanceof AgentResultEvent result) {
            // No AGUI direct mapping for "final assembled message" beyond the existing
            // TEXT/TOOL stream; forward as CUSTOM so SDK consumers can introspect the final Msg.
            out.add(custom("agentscope.agent_result", result));
            return out;
        }
        if (event instanceof AgentEndEvent) {
            // Close any stragglers before RUN_FINISHED so AGUI consumers see well-formed pairs.
            out.addAll(closeStragglers());
            out.add(new AguiEvent.RunFinished(threadId, runId));
            return out;
        }

        // Model call boundaries → AG-UI STEP_STARTED / STEP_FINISHED (stepName "model_call").
        if (event instanceof ModelCallStartEvent) {
            out.add(new AguiEvent.StepStarted(threadId, runId, "model_call"));
            return out;
        }
        if (event instanceof ModelCallEndEvent) {
            out.add(new AguiEvent.StepFinished(threadId, runId, "model_call"));
            return out;
        }

        // Text blocks → TEXT_MESSAGE_*.
        if (event instanceof TextBlockStartEvent e) {
            String mid = e.getBlockId();
            openTextMessages.add(mid);
            out.add(new AguiEvent.TextMessageStart(threadId, runId, mid, "assistant"));
            return out;
        }
        if (event instanceof TextBlockDeltaEvent e) {
            String mid = e.getBlockId();
            if (e.getDelta() != null && !e.getDelta().isEmpty()) {
                out.add(new AguiEvent.TextMessageContent(threadId, runId, mid, e.getDelta()));
            }
            return out;
        }
        if (event instanceof TextBlockEndEvent e) {
            String mid = e.getBlockId();
            if (openTextMessages.remove(mid)) {
                out.add(new AguiEvent.TextMessageEnd(threadId, runId, mid));
            }
            return out;
        }

        // Thinking blocks → REASONING_MESSAGE_* (only when reasoning is enabled, per AG-UI draft).
        if (event instanceof ThinkingBlockStartEvent e) {
            if (enableReasoning) {
                String mid = e.getBlockId();
                openReasoningMessages.add(mid);
                out.add(new AguiEvent.ReasoningMessageStart(threadId, runId, mid, "reasoning"));
            }
            return out;
        }
        if (event instanceof ThinkingBlockDeltaEvent e) {
            if (enableReasoning && e.getDelta() != null && !e.getDelta().isEmpty()) {
                out.add(
                        new AguiEvent.ReasoningMessageContent(
                                threadId, runId, e.getBlockId(), e.getDelta()));
            }
            return out;
        }
        if (event instanceof ThinkingBlockEndEvent e) {
            if (enableReasoning && openReasoningMessages.remove(e.getBlockId())) {
                out.add(new AguiEvent.ReasoningMessageEnd(threadId, runId, e.getBlockId()));
            }
            return out;
        }

        // Data blocks have no AG-UI counterpart → forward as CUSTOM.
        if (event instanceof DataBlockStartEvent e) {
            out.add(custom(AguiCustomEventNames.DATA_BLOCK_START, e));
            return out;
        }
        if (event instanceof DataBlockDeltaEvent e) {
            out.add(custom(AguiCustomEventNames.DATA_BLOCK_DELTA, e));
            return out;
        }
        if (event instanceof DataBlockEndEvent e) {
            out.add(custom(AguiCustomEventNames.DATA_BLOCK_END, e));
            return out;
        }

        // Tool calls → TOOL_CALL_*.
        if (event instanceof ToolCallStartEvent e) {
            openToolCalls.add(e.getToolCallId());
            toolResultName.put(e.getToolCallId(), e.getToolCallName());
            out.add(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, e.getToolCallId(), e.getToolCallName()));
            return out;
        }
        if (event instanceof ToolCallDeltaEvent e) {
            if (emitToolCallArgs && e.getDelta() != null && !e.getDelta().isEmpty()) {
                out.add(
                        new AguiEvent.ToolCallArgs(
                                threadId, runId, e.getToolCallId(), e.getDelta()));
            }
            return out;
        }
        if (event instanceof ToolCallEndEvent e) {
            if (openToolCalls.remove(e.getToolCallId())) {
                out.add(new AguiEvent.ToolCallEnd(threadId, runId, e.getToolCallId()));
            }
            return out;
        }

        // Tool result streaming: AG-UI only has a single TOOL_CALL_RESULT. Stream the internal
        // lifecycle as CUSTOM events, and emit the aggregated TOOL_CALL_RESULT at TOOL_RESULT_END.
        if (event instanceof ToolResultStartEvent e) {
            toolResultText.computeIfAbsent(e.getToolCallId(), k -> new StringBuilder());
            toolResultName.putIfAbsent(e.getToolCallId(), e.getToolCallName());
            out.add(custom(AguiCustomEventNames.TOOL_RESULT_START, e));
            return out;
        }
        if (event instanceof ToolResultTextDeltaEvent e) {
            if (e.getDelta() != null) {
                toolResultText
                        .computeIfAbsent(e.getToolCallId(), k -> new StringBuilder())
                        .append(e.getDelta());
            }
            out.add(custom(AguiCustomEventNames.TOOL_RESULT_TEXT_DELTA, e));
            return out;
        }
        if (event instanceof ToolResultDataDeltaEvent e) {
            out.add(custom(AguiCustomEventNames.TOOL_RESULT_DATA_DELTA, e));
            return out;
        }
        if (event instanceof ToolResultEndEvent e) {
            StringBuilder agg = toolResultText.remove(e.getToolCallId());
            String content = agg != null ? agg.toString() : null;
            String name = toolResultName.remove(e.getToolCallId());
            // Emit aggregated AG-UI result …
            out.add(
                    new AguiEvent.ToolCallResult(
                            threadId, runId, e.getToolCallId(), content, "tool", null));
            // … plus the precise end signal for SDK consumers.
            out.add(custom(AguiCustomEventNames.TOOL_RESULT_END, e));
            return out;
        }

        // Run control / iteration limits / HITL → CUSTOM.
        if (event instanceof ExceedMaxItersEvent e) {
            out.add(custom(AguiCustomEventNames.EXCEED_MAX_ITERS, e));
            return out;
        }
        if (event instanceof RequireUserConfirmEvent e) {
            out.add(custom(AguiCustomEventNames.REQUIRE_USER_CONFIRM, e));
            return out;
        }
        if (event instanceof UserConfirmResultEvent e) {
            out.add(custom(AguiCustomEventNames.USER_CONFIRM_RESULT, e));
            return out;
        }
        if (event instanceof RequireExternalExecutionEvent e) {
            out.add(custom(AguiCustomEventNames.REQUIRE_EXTERNAL_EXECUTION, e));
            return out;
        }
        if (event instanceof ExternalExecutionResultEvent e) {
            out.add(custom(AguiCustomEventNames.EXTERNAL_EXECUTION_RESULT, e));
            return out;
        }
        if (event instanceof RequestStopEvent e) {
            out.add(custom(AguiCustomEventNames.REQUEST_STOP, e));
            return out;
        }

        // Topology / hints.
        if (event instanceof SubagentExposedEvent e) {
            out.add(custom(AguiCustomEventNames.SUBAGENT_EXPOSED, e));
            return out;
        }
        if (event instanceof HintBlockEvent e) {
            out.add(custom(AguiCustomEventNames.HINT_BLOCK, e));
            return out;
        }

        // User-defined custom event — pass through preserving its original name and payload.
        if (event instanceof CustomEvent e) {
            out.add(new AguiEvent.Custom(threadId, runId, e.getName(), e.getValue()));
            return out;
        }

        // Unknown subtype — forward as opaque CUSTOM rather than dropping silently.
        out.add(custom(AguiCustomEventNames.PREFIX + "unknown", event));
        return out;
    }

    /**
     * Emit close events for any text/reasoning/tool messages that remained open when the agent
     * stream terminated without explicit END events. Called by the adapter on stream completion
     * or error.
     */
    public List<AguiEvent> closeStragglers() {
        List<AguiEvent> out = new ArrayList<>();
        for (String mid : openTextMessages) {
            out.add(new AguiEvent.TextMessageEnd(threadId, runId, mid));
        }
        openTextMessages.clear();
        for (String mid : openReasoningMessages) {
            out.add(new AguiEvent.ReasoningMessageEnd(threadId, runId, mid));
        }
        openReasoningMessages.clear();
        for (String tid : openToolCalls) {
            out.add(new AguiEvent.ToolCallEnd(threadId, runId, tid));
        }
        openToolCalls.clear();
        return out;
    }

    private AguiEvent.Custom custom(String name, Object value) {
        return new AguiEvent.Custom(threadId, runId, name, value);
    }
}
