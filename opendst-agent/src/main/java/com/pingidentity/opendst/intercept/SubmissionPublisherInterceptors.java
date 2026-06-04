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
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;

/**
 * Functional module rerouting {@code Flow.SubmissionPublisher} delivery off the common pool.
 *
 * <p>A {@code SubmissionPublisher} created without an explicit executor delivers items to
 * subscribers on {@code ForkJoinPool.commonPool()} — platform workers that escape the deterministic
 * per-node scheduler. The no-arg and {@code (Executor,int)} constructors funnel into
 * {@code SubmissionPublisher(Executor, int, BiConsumer)}; this advice replaces a common-pool
 * executor argument with {@link CompletableFutureInterceptors#NODE_VTHREAD_EXECUTOR} inside a node,
 * so delivery runs on node-scheduled virtual threads. Explicitly-supplied executors are left alone;
 * no-op outside a simulation.
 */
public final class SubmissionPublisherInterceptors {

    /** Replaces a common-pool executor passed to {@code SubmissionPublisher(Executor, int, BiConsumer)}. */
    @Intercepts(
            value = "java.util.concurrent.SubmissionPublisher#<init>(Executor,int,BiConsumer)",
            comment = "SubmissionPublisher delivers on the common pool by default; reroute to a "
                    + "node-scheduled virtual-thread executor.")
    public static final class ConstructorAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 0, readOnly = false) Executor executor) {
            if (executor instanceof ForkJoinPool && currentNodeOrNull() != null) {
                executor = CompletableFutureInterceptors.NODE_VTHREAD_EXECUTOR;
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.util.concurrent.SubmissionPublisher"))
                .transform((builder, _, _, _, _) -> builder.visit(to(ConstructorAdvice.class)
                        .on(isConstructor().and(takesArguments(Executor.class, int.class, BiConsumer.class)))))
                .asTerminalTransformation();
    }

    private SubmissionPublisherInterceptors() {
        // Prevent instantiation
    }
}
