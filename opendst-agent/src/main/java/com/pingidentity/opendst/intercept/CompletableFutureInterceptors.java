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
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Return;

/**
 * Functional module rerouting {@code CompletableFuture.*Async} default execution onto the node.
 *
 * <p>{@code CompletableFuture}'s async stages without an explicit executor run on its
 * {@code ASYNC_POOL}, which is the shared {@code ForkJoinPool} common pool (when common-pool
 * parallelism &gt; 1). That escapes the deterministic per-node scheduler. These advices substitute
 * a node-bound executor — {@link #NODE_VTHREAD_EXECUTOR}, which runs each task on a virtual thread
 * that, inside a node, is scheduled by {@code node::scheduleNow}. The stage therefore stays
 * concurrent (its own virtual thread, interleavable on the node timeline) and deterministic;
 * completion and a waiter's {@code get()}/{@code join()} ride on the existing cooperative
 * park/unpark machinery.
 *
 * <p>Two seams cover the no-executor surface: instance {@code *Async} methods read
 * {@code defaultExecutor()}, while the static {@code supplyAsync(Supplier)}/{@code runAsync(Runnable)}
 * factories read {@code ASYNC_POOL} directly and pass it to {@code asyncSupplyStage}/
 * {@code asyncRunStage}. Explicitly-supplied executors are left alone (only the common pool is
 * rerouted). Outside a simulation both advices are no-ops.
 */
public final class CompletableFutureInterceptors {

    /**
     * Executor that runs each task on a fresh virtual thread. Inside a node, {@code ThreadBuilders}
     * interception binds that virtual thread to the node's deterministic scheduler. Must be
     * {@code public} — referenced from advice inlined into {@code java.util.concurrent}.
     */
    public static final Executor NODE_VTHREAD_EXECUTOR = task -> {
        Thread.ofVirtual().start(task);
    };

    /** Replaces {@code CompletableFuture#defaultExecutor()} (used by instance {@code *Async} methods). */
    @Intercepts(
            value = "java.util.concurrent.CompletableFuture#defaultExecutor()",
            comment = "Instance *Async methods run on the common pool by default, escaping the "
                    + "deterministic per-node scheduler. Substitute a node-bound virtual-thread executor.")
    public static final class DefaultExecutorAdvice {
        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(@Return(readOnly = false) Executor result) {
            if (currentNodeOrNull() != null) {
                result = NODE_VTHREAD_EXECUTOR;
            }
        }
    }

    /**
     * Replaces the common-pool executor argument of {@code asyncSupplyStage}/{@code asyncRunStage}
     * (used by the static {@code supplyAsync}/{@code runAsync} factories without an executor).
     */
    @Intercepts(
            value = "java.util.concurrent.CompletableFuture#asyncSupplyStage(Executor,Supplier)",
            comment = "Static supplyAsync without an executor uses the common pool; reroute to the node.")
    @Intercepts(
            value = "java.util.concurrent.CompletableFuture#asyncRunStage(Executor,Runnable)",
            comment = "Static runAsync without an executor uses the common pool; reroute to the node.")
    public static final class AsyncStageAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 0, readOnly = false) Executor executor) {
            if (executor instanceof ForkJoinPool && currentNodeOrNull() != null) {
                executor = NODE_VTHREAD_EXECUTOR;
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.util.concurrent.CompletableFuture"))
                .transform((builder, _, _, _, _) -> builder.visit(to(DefaultExecutorAdvice.class)
                                .on(named("defaultExecutor").and(takesArguments(0))))
                        .visit(to(AsyncStageAdvice.class)
                                .on(named("asyncSupplyStage").or(named("asyncRunStage")))))
                .asTerminalTransformation();
    }

    private CompletableFutureInterceptors() {
        // Prevent instantiation
    }
}
