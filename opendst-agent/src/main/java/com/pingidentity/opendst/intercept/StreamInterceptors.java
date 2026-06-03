/*
 * Copyright 2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.intercept;

import static com.pingidentity.opendst.simulator.Node.currentNodeOrNull;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Return;

/** Functional module forcing parallel streams to execute sequentially under simulation. */
public final class StreamInterceptors {

    /**
     * Forces {@code java.util.stream.AbstractPipeline#isParallel()} to report {@code false} inside
     * a simulation.
     *
     * <p>A parallel stream's terminal operation dispatches in {@code AbstractPipeline.evaluate()}
     * solely on {@code isParallel()}: when {@code true} it submits work to the shared
     * {@code ForkJoinPool} common pool (running on platform {@code ForkJoinWorkerThread}s that
     * escape the deterministic per-node scheduler); when {@code false} it runs the terminal op
     * sequentially on the calling thread. Reporting {@code false} therefore keeps the whole
     * computation on the node's deterministic timeline.
     *
     * <p>This is result-equivalent for well-behaved (stateless, non-interfering, associative)
     * stream operations — the only contract the Stream API makes about parallel execution — so it
     * changes execution mode, not results. Outside a simulation the advice is a no-op.
     */
    @Intercepts(
            value = "java.util.stream.AbstractPipeline#isParallel()",
            comment = "Parallel streams run on the shared ForkJoinPool common pool, escaping the "
                    + "deterministic per-node scheduler. Forcing sequential evaluation keeps execution "
                    + "on the node and is result-equivalent for well-behaved stream operations.")
    public static final class IsParallelAdvice {
        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(@Return(readOnly = false) boolean parallel) {
            if (parallel && currentNodeOrNull() != null) {
                parallel = false;
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.util.stream.AbstractPipeline"))
                .transform((builder, _, _, _, _) -> builder.visit(to(IsParallelAdvice.class)
                        .on(named("isParallel").and(takesNoArguments()).and(returns(boolean.class)))))
                .asTerminalTransformation();
    }

    private StreamInterceptors() {
        // Prevent instantiation
    }
}
