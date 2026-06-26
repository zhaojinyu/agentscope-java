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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.converter.AguiToolConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * Adapter that bridges AgentScope {@link EventStreamableAgent}s to the AG-UI protocol.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>Convert AG-UI {@link RunAgentInput} into AgentScope {@link Msg}s.
 *   <li>Optionally inject frontend tool schemas into the agent's toolkit (per
 *       {@link AguiAdapterConfig#getToolMergeMode()}).
 *   <li>Subscribe to {@code agent.streamEvents(msgs, ctx)} for fine-grained {@link AgentEvent}s.
 *   <li>Translate each {@code AgentEvent} into AG-UI {@link AguiEvent}s via
 *       {@link AgentEventToAguiMapper}.
 *   <li>Wrap the stream with a leading {@code RUN_STARTED} and a trailing {@code RUN_FINISHED}
 *       (or {@code RUN_ERROR + RUN_FINISHED} on failure).
 * </ol>
 *
 * <p>Events that have no AG-UI spec counterpart (model-call boundaries, streaming tool results,
 * HITL signals, etc.) are forwarded as {@link AguiEvent.Custom} with names from
 * {@link io.agentscope.core.agui.event.AguiCustomEventNames}, preserving full event fidelity for
 * AgentScope-aware SDK clients while remaining spec-safe for generic AG-UI consumers.
 */
public class AguiAgentAdapter {

    public static final String RUNTIME_CONTEXT_THREAD_ID_KEY = "agui.threadId";
    public static final String RUNTIME_CONTEXT_RUN_ID_KEY = "agui.runId";
    public static final String RUNTIME_CONTEXT_MESSAGES_KEY = "agui.messages";
    public static final String RUNTIME_CONTEXT_TOOLS_KEY = "agui.tools";
    public static final String RUNTIME_CONTEXT_CONTEXT_KEY = "agui.context";
    public static final String RUNTIME_CONTEXT_STATE_KEY = "agui.state";
    public static final String RUNTIME_CONTEXT_FORWARDED_PROPS_KEY = "agui.forwardedProps";

    private final EventStreamableAgent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;
    private final AguiToolConverter toolConverter;

    public AguiAgentAdapter(EventStreamableAgent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();
        this.toolConverter = new AguiToolConverter();
    }

    /**
     * Run the agent against the given AG-UI input and emit the protocol-level event stream.
     */
    public Flux<AguiEvent> run(RunAgentInput input) {
        return Flux.defer(
                () -> {
                    String threadId = input.getThreadId();
                    String runId = input.getRunId();
                    List<Msg> msgs = messageConverter.toMsgList(input.getMessages());
                    RuntimeContext ctx = buildRuntimeContext(input);

                    ToolInjection injection;
                    Flux<AgentEvent> agentEvents;
                    try {
                        injection = injectFrontendTools(input);
                        agentEvents = agent.streamEvents(msgs, ctx);
                        if (agentEvents == null) {
                            throw new IllegalStateException(
                                    "agent.streamEvents() returned null for run " + runId);
                        }
                    } catch (Throwable error) {
                        return Flux.concat(
                                Flux.just(new AguiEvent.RunStarted(threadId, runId, null, input)),
                                errorEvents(threadId, runId, error));
                    }

                    AgentEventToAguiMapper mapper =
                            new AgentEventToAguiMapper(
                                    threadId,
                                    runId,
                                    config.isEnableReasoning(),
                                    config.isEmitToolCallArgs());
                    ToolInjection activeInjection = injection;

                    Flux<AguiEvent> mapped =
                            agentEvents
                                    .concatMapIterable(mapper::map)
                                    .concatWith(
                                            Flux.defer(
                                                    () ->
                                                            Flux.fromIterable(
                                                                    mapper.closeStragglers())));

                    return Flux.concat(
                                    Flux.just(
                                            new AguiEvent.RunStarted(threadId, runId, null, input)),
                                    mapped)
                            .doFinally(s -> activeInjection.close())
                            .onErrorResume(
                                    error ->
                                            Flux.concat(
                                                    Flux.fromIterable(mapper.closeStragglers()),
                                                    errorEvents(threadId, runId, error)));
                });
    }

    private RuntimeContext buildRuntimeContext(RunAgentInput input) {
        return RuntimeContext.builder()
                .sessionId(input.getThreadId())
                .put(RunAgentInput.class, input)
                .put(RUNTIME_CONTEXT_THREAD_ID_KEY, input.getThreadId())
                .put(RUNTIME_CONTEXT_RUN_ID_KEY, input.getRunId())
                .put(RUNTIME_CONTEXT_MESSAGES_KEY, input.getMessages())
                .put(RUNTIME_CONTEXT_TOOLS_KEY, input.getTools())
                .put(RUNTIME_CONTEXT_CONTEXT_KEY, input.getContext())
                .put(RUNTIME_CONTEXT_STATE_KEY, input.getState())
                .put(RUNTIME_CONTEXT_FORWARDED_PROPS_KEY, input.getForwardedProps())
                .build();
    }

    private ToolInjection injectFrontendTools(RunAgentInput input) {
        if (!input.hasTools()) {
            return ToolInjection.empty();
        }

        ToolMergeMode mergeMode =
                config.getToolMergeMode() != null
                        ? config.getToolMergeMode()
                        : ToolMergeMode.MERGE_FRONTEND_PRIORITY;
        if (mergeMode == ToolMergeMode.AGENT_ONLY) {
            return ToolInjection.empty();
        }

        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return ToolInjection.empty();
        }

        Map<String, AgentTool> previousTools = new LinkedHashMap<>();
        if (mergeMode == ToolMergeMode.FRONTEND_ONLY) {
            for (String toolName : toolkit.getToolNames()) {
                AgentTool previousTool = toolkit.getTool(toolName);
                if (previousTool != null) {
                    previousTools.put(toolName, previousTool);
                    toolkit.removeTool(toolName);
                }
            }
        }

        List<SchemaOnlyTool> registeredTools = new ArrayList<>();
        for (ToolSchema schema : toolConverter.toToolSchemaList(input.getTools())) {
            AgentTool previousTool = toolkit.getTool(schema.getName());
            if (previousTool != null) {
                previousTools.putIfAbsent(schema.getName(), previousTool);
            }

            SchemaOnlyTool frontendTool = new SchemaOnlyTool(schema);
            toolkit.registerAgentTool(frontendTool);
            registeredTools.add(frontendTool);
        }

        return new ToolInjection(toolkit, registeredTools, previousTools);
    }

    private Flux<AguiEvent> errorEvents(String threadId, String runId, Throwable error) {
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return Flux.just(
                new AguiEvent.RunError(threadId, runId, errorMessage, mapErrorCode(error)),
                new AguiEvent.RunFinished(threadId, runId));
    }

    private static String mapErrorCode(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT_ERROR";
        }
        if (error instanceof java.lang.InterruptedException) {
            return "INTERRUPTED_ERROR";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return "INVALID_INPUT_ERROR";
        }
        return "INTERNAL_ERROR";
    }

    private static class ToolInjection {
        private static final ToolInjection EMPTY =
                new ToolInjection(null, Collections.emptyList(), Collections.emptyMap());

        private final Toolkit toolkit;
        private final List<SchemaOnlyTool> registeredTools;
        private final Map<String, AgentTool> previousTools;

        ToolInjection(
                Toolkit toolkit,
                List<SchemaOnlyTool> registeredTools,
                Map<String, AgentTool> previousTools) {
            this.toolkit = toolkit;
            this.registeredTools = registeredTools;
            this.previousTools = previousTools;
        }

        static ToolInjection empty() {
            return EMPTY;
        }

        void close() {
            if (toolkit == null) {
                return;
            }
            for (int i = registeredTools.size() - 1; i >= 0; i--) {
                SchemaOnlyTool tool = registeredTools.get(i);
                toolkit.removeToolIfSame(tool.getName(), tool);
            }
            for (Map.Entry<String, AgentTool> entry : previousTools.entrySet()) {
                if (toolkit.getTool(entry.getKey()) == null) {
                    toolkit.registerAgentTool(entry.getValue());
                }
            }
        }
    }
}
