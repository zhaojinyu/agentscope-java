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
package io.agentscope.core.agui.event;

/**
 * Names for {@code agentscope.*}-namespaced extension events carried over the AG-UI protocol via
 * {@link AguiEvent.Custom}.
 *
 * <p>AG-UI's standard event set does not cover every fine-grained {@code AgentEvent} type the
 * core framework emits — examples include model-call boundaries, streaming tool results, data
 * blocks, hint blocks, human-in-the-loop signals, and the request-stop signal. To stay
 * spec-compatible with AG-UI consumers (CopilotKit etc.) while preserving full fidelity for
 * AgentScope-aware clients, those events are forwarded as {@code CUSTOM} events whose {@code
 * name} field is one of the constants below.
 *
 * <p>Client SDKs that understand the namespace can decode the {@code value} payload back into
 * a typed {@code AgentEvent}; clients that don't will surface the events as opaque custom
 * extensions and can safely ignore them.
 */
public final class AguiCustomEventNames {

    /** Common prefix for all AgentScope namespaced events. */
    public static final String PREFIX = "agentscope.";

    // ---- Data blocks (non-AG-UI block type) ----
    public static final String DATA_BLOCK_START = PREFIX + "data_block.start";
    public static final String DATA_BLOCK_DELTA = PREFIX + "data_block.delta";
    public static final String DATA_BLOCK_END = PREFIX + "data_block.end";

    // ---- Streaming tool result lifecycle (AG-UI only has aggregate TOOL_CALL_RESULT) ----
    public static final String TOOL_RESULT_START = PREFIX + "tool_result.start";
    public static final String TOOL_RESULT_TEXT_DELTA = PREFIX + "tool_result.text_delta";
    public static final String TOOL_RESULT_DATA_DELTA = PREFIX + "tool_result.data_delta";
    public static final String TOOL_RESULT_END = PREFIX + "tool_result.end";

    // ---- Human-in-the-loop ----
    public static final String REQUIRE_USER_CONFIRM = PREFIX + "require_user_confirm";
    public static final String USER_CONFIRM_RESULT = PREFIX + "user_confirm_result";
    public static final String REQUIRE_EXTERNAL_EXECUTION = PREFIX + "require_external_execution";
    public static final String EXTERNAL_EXECUTION_RESULT = PREFIX + "external_execution_result";

    // ---- Run control ----
    public static final String REQUEST_STOP = PREFIX + "request_stop";
    public static final String EXCEED_MAX_ITERS = PREFIX + "exceed_max_iters";

    // ---- Topology / hints ----
    public static final String SUBAGENT_EXPOSED = PREFIX + "subagent_exposed";
    public static final String HINT_BLOCK = PREFIX + "hint_block";

    private AguiCustomEventNames() {}
}
