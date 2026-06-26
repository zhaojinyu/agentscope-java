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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * AGUI-local capability view of an agent that can emit fine-grained {@link AgentEvent}s.
 *
 * <p>Defined inside the agui module on purpose: {@code ReActAgent} and {@code HarnessAgent} both
 * already expose a {@code streamEvents(List<Msg>, RuntimeContext)} method, but they do not share
 * a public interface for it. Rather than force a cross-module change to agentscope-core /
 * agentscope-harness, the adapter consumes this small interface and {@link #from(Agent)} bridges
 * any compatible {@link Agent} via a cached {@link MethodHandle}.
 *
 * <p>If you control the agent type, you can also implement this interface directly to skip the
 * reflective bridge.
 */
public interface EventStreamableAgent {

    /**
     * Stream fine-grained {@link AgentEvent}s for the given input messages.
     *
     * @param msgs input messages
     * @param context per-invocation runtime context; may be {@code null} for default scope
     * @return event stream covering the full invocation lifecycle
     */
    Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context);

    /** Returns the agent's {@link Toolkit}, or {@code null} if no toolkit is exposed. */
    Toolkit getToolkit();

    /**
     * Wrap any {@link Agent} whose concrete class declares a public
     * {@code streamEvents(List<Msg>, RuntimeContext)} method. Currently {@code ReActAgent} and
     * {@code HarnessAgent} both qualify.
     *
     * @throws IllegalArgumentException if {@code agent} is null or its class does not expose a
     *     matching {@code streamEvents} method
     */
    static EventStreamableAgent from(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        return new ReflectiveBridge(agent);
    }

    /** Reflective bridge over an {@link Agent} that has the {@code streamEvents} method. */
    final class ReflectiveBridge implements EventStreamableAgent {
        private static final MethodType STREAM_EVENTS_TYPE =
                MethodType.methodType(Flux.class, List.class, RuntimeContext.class);

        private final Agent agent;
        private final MethodHandle streamEvents;

        ReflectiveBridge(Agent agent) {
            this.agent = agent;
            MethodHandle handle;
            try {
                handle =
                        MethodHandles.publicLookup()
                                .findVirtual(agent.getClass(), "streamEvents", STREAM_EVENTS_TYPE);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalArgumentException(
                        "Agent "
                                + agent.getClass().getName()
                                + " does not expose a public 'streamEvents(List<Msg>,"
                                + " RuntimeContext)' method required by the AG-UI adapter",
                        e);
            }
            this.streamEvents = handle.bindTo(agent);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Flux<AgentEvent> streamEvents(List<Msg> msgs, RuntimeContext context) {
            try {
                return (Flux<AgentEvent>) streamEvents.invoke(msgs, context);
            } catch (Throwable t) {
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("Failed to invoke streamEvents", t);
            }
        }

        @Override
        public Toolkit getToolkit() {
            return agent.getToolkit();
        }
    }
}
