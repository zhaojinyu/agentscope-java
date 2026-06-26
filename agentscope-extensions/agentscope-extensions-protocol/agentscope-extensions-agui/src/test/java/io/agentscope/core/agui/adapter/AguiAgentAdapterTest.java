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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.event.AguiCustomEventNames;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Adapter-level tests: verifies the pipeline wrapping (RUN_STARTED / RUN_FINISHED / RUN_ERROR),
 * straggler cleanup, and that the adapter feeds {@code agent.streamEvents()} output into the
 * mapper. Detailed event-mapping is covered by {@link AgentEventToAguiMapperTest}.
 */
class AguiAgentAdapterTest {

    private EventStreamableAgent agent;
    private AguiAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        agent = mock(EventStreamableAgent.class);
        adapter = new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());
    }

    private RunAgentInput basicInput() {
        return RunAgentInput.builder()
                .threadId("thread-1")
                .runId("run-1")
                .messages(List.of(AguiMessage.userMessage("m1", "hi")))
                .build();
    }

    @Test
    void emptyAgentStreamEmitsRunStartedOnly() {
        when(agent.streamEvents(anyList(), any())).thenReturn(Flux.empty());

        // An empty agent stream emits only RUN_STARTED — the mapper owns RUN_FINISHED (driven by
        // AGENT_END). A well-behaved agent always emits AGENT_END; this test pins the contract
        // so we notice if that assumption changes.
        StepVerifier.create(adapter.run(basicInput()))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .verifyComplete();
    }

    @Test
    void textStreamProducesAguiTextEvents() {
        Flux<AgentEvent> events =
                Flux.just(
                        new AgentStartEvent("thread-1", "run-1", "test-agent"),
                        new TextBlockStartEvent("run-1", "b1"),
                        new TextBlockDeltaEvent("run-1", "b1", "hello "),
                        new TextBlockDeltaEvent("run-1", "b1", "world"),
                        new TextBlockEndEvent("run-1", "b1"),
                        new AgentEndEvent("run-1"));
        when(agent.streamEvents(anyList(), any())).thenReturn(events);

        StepVerifier.create(adapter.run(basicInput()))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageStart)
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.TextMessageContent c
                                        && c.delta().equals("hello "))
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.TextMessageContent c
                                        && c.delta().equals("world"))
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageEnd)
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void modelCallBoundaryProducesStepEvents() {
        Flux<AgentEvent> events =
                Flux.just(
                        new AgentStartEvent("thread-1", "run-1", "test-agent"),
                        new ModelCallStartEvent("run-1"),
                        new ModelCallEndEvent("run-1", null),
                        new AgentEndEvent("run-1"));
        when(agent.streamEvents(anyList(), any())).thenReturn(events);

        StepVerifier.create(adapter.run(basicInput()))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.StepStarted s
                                        && s.stepName().equals("model_call"))
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.StepFinished s
                                        && s.stepName().equals("model_call"))
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void requireUserConfirmEmitsNamespacedCustom() {
        Flux<AgentEvent> events =
                Flux.just(
                        new AgentStartEvent("thread-1", "run-1", "test-agent"),
                        new RequireUserConfirmEvent("run-1", Collections.emptyList()),
                        new AgentEndEvent("run-1"));
        when(agent.streamEvents(anyList(), any())).thenReturn(events);

        StepVerifier.create(adapter.run(basicInput()))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.Custom c
                                        && c.name()
                                                .equals(AguiCustomEventNames.REQUIRE_USER_CONFIRM))
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void synchronousAgentErrorEmitsRunErrorWrapped() {
        when(agent.streamEvents(anyList(), any())).thenThrow(new IllegalStateException("boom"));

        StepVerifier.create(adapter.run(basicInput()))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.RunError err
                                        && err.message().equals("boom")
                                        && err.code().equals("INVALID_INPUT_ERROR"))
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }

    @Test
    void streamErrorEmitsRunErrorAndClosesOpenBlocks() {
        Flux<AgentEvent> events =
                Flux.<AgentEvent>just(
                                new AgentStartEvent("thread-1", "run-1", "test-agent"),
                                new TextBlockStartEvent("run-1", "b1"))
                        .concatWith(Flux.error(new RuntimeException("stream blew up")));
        when(agent.streamEvents(anyList(), any())).thenReturn(events);

        StepVerifier.create(adapter.run(basicInput()))
                .expectNextMatches(e -> e instanceof AguiEvent.RunStarted)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageStart)
                .expectNextMatches(e -> e instanceof AguiEvent.TextMessageEnd)
                .expectNextMatches(
                        e ->
                                e instanceof AguiEvent.RunError err
                                        && err.code().equals("INTERNAL_ERROR"))
                .expectNextMatches(e -> e instanceof AguiEvent.RunFinished)
                .verifyComplete();
    }
}
