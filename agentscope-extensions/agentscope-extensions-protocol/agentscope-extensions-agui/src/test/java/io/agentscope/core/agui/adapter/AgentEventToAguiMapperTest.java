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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.event.AguiCustomEventNames;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEndEvent;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventToAguiMapperTest {

    private static final String THREAD = "thread-1";
    private static final String RUN = "run-1";

    private AgentEventToAguiMapper newMapper(boolean reasoning, boolean toolArgs) {
        return new AgentEventToAguiMapper(THREAD, RUN, reasoning, toolArgs);
    }

    // --------------- lifecycle ---------------

    @Test
    void agentStartIsAbsorbed() {
        AgentEventToAguiMapper m = newMapper(false, true);
        assertTrue(m.map(new AgentStartEvent("s", "r", "agent")).isEmpty());
    }

    @Test
    void agentEndMapsToRunFinished() {
        AgentEventToAguiMapper m = newMapper(false, true);
        List<AguiEvent> out = m.map(new AgentEndEvent("r"));
        assertEquals(1, out.size());
        assertInstanceOf(AguiEvent.RunFinished.class, out.get(0));
    }

    @Test
    void agentResultEmitsCustom() {
        AgentEventToAguiMapper m = newMapper(false, true);
        List<AguiEvent> out = m.map(new AgentResultEvent(null));
        AguiEvent.Custom c = assertInstanceOf(AguiEvent.Custom.class, single(out));
        assertEquals("agentscope.agent_result", c.name());
    }

    // --------------- model call ---------------

    @Test
    void modelCallStartEndMapsToStepStartedFinished() {
        AgentEventToAguiMapper m = newMapper(false, true);
        AguiEvent.StepStarted s =
                assertInstanceOf(
                        AguiEvent.StepStarted.class, single(m.map(new ModelCallStartEvent("r"))));
        assertEquals("model_call", s.stepName());

        AguiEvent.StepFinished f =
                assertInstanceOf(
                        AguiEvent.StepFinished.class,
                        single(m.map(new ModelCallEndEvent("r", null))));
        assertEquals("model_call", f.stepName());
    }

    // --------------- text blocks ---------------

    @Test
    void textBlockLifecycleMapsToTextMessage() {
        AgentEventToAguiMapper m = newMapper(false, true);
        AguiEvent.TextMessageStart s =
                assertInstanceOf(
                        AguiEvent.TextMessageStart.class,
                        single(m.map(new TextBlockStartEvent("r", "b1"))));
        assertEquals("b1", s.messageId());
        assertEquals("assistant", s.role());

        AguiEvent.TextMessageContent d =
                assertInstanceOf(
                        AguiEvent.TextMessageContent.class,
                        single(m.map(new TextBlockDeltaEvent("r", "b1", "hello"))));
        assertEquals("hello", d.delta());

        AguiEvent.TextMessageEnd e =
                assertInstanceOf(
                        AguiEvent.TextMessageEnd.class,
                        single(m.map(new TextBlockEndEvent("r", "b1"))));
        assertEquals("b1", e.messageId());
    }

    @Test
    void textBlockDeltaEmptyOrNullIsDropped() {
        AgentEventToAguiMapper m = newMapper(false, true);
        m.map(new TextBlockStartEvent("r", "b1"));
        assertTrue(m.map(new TextBlockDeltaEvent("r", "b1", "")).isEmpty());
        assertTrue(m.map(new TextBlockDeltaEvent("r", "b1", null)).isEmpty());
    }

    // --------------- thinking blocks ---------------

    @Test
    void thinkingBlockGatedByEnableReasoningFlag() {
        AgentEventToAguiMapper off = newMapper(false, true);
        assertTrue(off.map(new ThinkingBlockStartEvent("r", "t1")).isEmpty());
        assertTrue(off.map(new ThinkingBlockDeltaEvent("r", "t1", "think")).isEmpty());
        assertTrue(off.map(new ThinkingBlockEndEvent("r", "t1")).isEmpty());

        AgentEventToAguiMapper on = newMapper(true, true);
        assertInstanceOf(
                AguiEvent.ReasoningMessageStart.class,
                single(on.map(new ThinkingBlockStartEvent("r", "t1"))));
        assertInstanceOf(
                AguiEvent.ReasoningMessageContent.class,
                single(on.map(new ThinkingBlockDeltaEvent("r", "t1", "think"))));
        assertInstanceOf(
                AguiEvent.ReasoningMessageEnd.class,
                single(on.map(new ThinkingBlockEndEvent("r", "t1"))));
    }

    // --------------- data blocks (custom namespace) ---------------

    @Test
    void dataBlockEventsMapToCustom() {
        AgentEventToAguiMapper m = newMapper(false, true);
        assertCustomName(
                m.map(new DataBlockStartEvent("r", "d1")), AguiCustomEventNames.DATA_BLOCK_START);
        assertCustomName(
                m.map(new DataBlockDeltaEvent("r", "d1", "payload")),
                AguiCustomEventNames.DATA_BLOCK_DELTA);
        assertCustomName(
                m.map(new DataBlockEndEvent("r", "d1")), AguiCustomEventNames.DATA_BLOCK_END);
    }

    // --------------- tool calls ---------------

    @Test
    void toolCallLifecycleMapsToToolCallEvents() {
        AgentEventToAguiMapper m = newMapper(false, true);
        AguiEvent.ToolCallStart s =
                assertInstanceOf(
                        AguiEvent.ToolCallStart.class,
                        single(m.map(new ToolCallStartEvent("r", "tc1", "search"))));
        assertEquals("tc1", s.toolCallId());
        assertEquals("search", s.toolCallName());

        assertInstanceOf(
                AguiEvent.ToolCallArgs.class,
                single(m.map(new ToolCallDeltaEvent("r", "tc1", "search", "{\"q\":"))));
        assertInstanceOf(
                AguiEvent.ToolCallEnd.class,
                single(m.map(new ToolCallEndEvent("r", "tc1", "search"))));
    }

    @Test
    void toolCallArgsGatedByEmitFlag() {
        AgentEventToAguiMapper m = newMapper(false, false);
        m.map(new ToolCallStartEvent("r", "tc1", "search"));
        assertTrue(m.map(new ToolCallDeltaEvent("r", "tc1", "search", "{}")).isEmpty());
    }

    // --------------- tool results: aggregated AG-UI result + per-event CUSTOM ---------------

    @Test
    void toolResultStreamAggregatesIntoSingleResult() {
        AgentEventToAguiMapper m = newMapper(false, true);
        assertCustomName(
                m.map(new ToolResultStartEvent("r", "tc1", "search")),
                AguiCustomEventNames.TOOL_RESULT_START);
        assertCustomName(
                m.map(new ToolResultTextDeltaEvent("r", "tc1", "search", "abc")),
                AguiCustomEventNames.TOOL_RESULT_TEXT_DELTA);
        assertCustomName(
                m.map(new ToolResultTextDeltaEvent("r", "tc1", "search", "def")),
                AguiCustomEventNames.TOOL_RESULT_TEXT_DELTA);

        List<AguiEvent> end = m.map(new ToolResultEndEvent("r", "tc1", "search", null));
        assertEquals(2, end.size());
        AguiEvent.ToolCallResult result =
                assertInstanceOf(AguiEvent.ToolCallResult.class, end.get(0));
        assertEquals("tc1", result.toolCallId());
        assertEquals("abcdef", result.content());
        AguiEvent.Custom endCustom = assertInstanceOf(AguiEvent.Custom.class, end.get(1));
        assertEquals(AguiCustomEventNames.TOOL_RESULT_END, endCustom.name());
    }

    @Test
    void toolResultDataDeltaEmitsCustom() {
        AgentEventToAguiMapper m = newMapper(false, true);
        assertCustomName(
                m.map(new ToolResultDataDeltaEvent("r", "tc1", "search", null)),
                AguiCustomEventNames.TOOL_RESULT_DATA_DELTA);
    }

    // --------------- run control / HITL ---------------

    @Test
    void runControlEventsMapToCustom() {
        AgentEventToAguiMapper m = newMapper(false, true);
        assertCustomName(
                m.map(new ExceedMaxItersEvent("r", 10, 11)), AguiCustomEventNames.EXCEED_MAX_ITERS);
        assertCustomName(
                m.map(new RequireUserConfirmEvent("r", Collections.emptyList())),
                AguiCustomEventNames.REQUIRE_USER_CONFIRM);
        assertCustomName(
                m.map(new UserConfirmResultEvent("r", Collections.emptyList())),
                AguiCustomEventNames.USER_CONFIRM_RESULT);
        assertCustomName(
                m.map(new RequireExternalExecutionEvent("r", Collections.emptyList())),
                AguiCustomEventNames.REQUIRE_EXTERNAL_EXECUTION);
        assertCustomName(
                m.map(new ExternalExecutionResultEvent("r", Collections.emptyList())),
                AguiCustomEventNames.EXTERNAL_EXECUTION_RESULT);
        assertCustomName(
                m.map(new RequestStopEvent("user cancelled")), AguiCustomEventNames.REQUEST_STOP);
    }

    @Test
    void topologyAndHintEventsMapToCustom() {
        AgentEventToAguiMapper m = newMapper(false, true);
        assertCustomName(
                m.map(new SubagentExposedEvent("sub-1", "agent-1", "sess-1", "researcher")),
                AguiCustomEventNames.SUBAGENT_EXPOSED);
        assertCustomName(
                m.map(new HintBlockEvent("r", "h1", "memory", "remember X")),
                AguiCustomEventNames.HINT_BLOCK);
    }

    @Test
    void userCustomEventPassesThroughNameAndValue() {
        AgentEventToAguiMapper m = newMapper(false, true);
        Map<String, Object> payload = Map.of("k", "v");
        List<AguiEvent> out = m.map(new CustomEvent("my.event", payload));
        AguiEvent.Custom c = assertInstanceOf(AguiEvent.Custom.class, single(out));
        assertEquals("my.event", c.name());
        assertEquals(payload, c.value());
    }

    // --------------- straggler cleanup ---------------

    @Test
    void closeStragglersEmitsEndForUnclosedOpens() {
        AgentEventToAguiMapper m = newMapper(true, true);
        m.map(new TextBlockStartEvent("r", "text-1"));
        m.map(new ThinkingBlockStartEvent("r", "thk-1"));
        m.map(new ToolCallStartEvent("r", "tc-1", "search"));

        List<AguiEvent> stragglers = m.closeStragglers();
        assertEquals(3, stragglers.size());
        assertTrue(stragglers.stream().anyMatch(e -> e instanceof AguiEvent.TextMessageEnd));
        assertTrue(stragglers.stream().anyMatch(e -> e instanceof AguiEvent.ReasoningMessageEnd));
        assertTrue(stragglers.stream().anyMatch(e -> e instanceof AguiEvent.ToolCallEnd));

        // Calling again yields nothing (state is cleared).
        assertTrue(m.closeStragglers().isEmpty());
    }

    @Test
    void closedTextMessageDoesNotReappearInStragglers() {
        AgentEventToAguiMapper m = newMapper(false, true);
        m.map(new TextBlockStartEvent("r", "text-1"));
        m.map(new TextBlockEndEvent("r", "text-1"));
        assertTrue(m.closeStragglers().isEmpty());
    }

    // --------------- helpers ---------------

    private static AguiEvent single(List<AguiEvent> events) {
        assertEquals(1, events.size(), () -> "expected 1 event but got: " + events);
        return events.get(0);
    }

    private static void assertCustomName(List<AguiEvent> events, String expectedName) {
        AguiEvent.Custom c = assertInstanceOf(AguiEvent.Custom.class, single(events));
        assertEquals(expectedName, c.name());
    }
}
