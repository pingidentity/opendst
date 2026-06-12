/*
 * Copyright 2025-2026 Ping Identity Corporation
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

import static com.pingidentity.opendst.intercept.ThreadsInterceptors.installSimulatorThreadCallback;
import static java.lang.System.setProperty;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.pingidentity.opendst.simulator.Simulator.SimulationError;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ThreadLocalRandom;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Default;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.InjectionStrategy.UsingReflection;
import net.bytebuddy.agent.builder.AgentBuilder.Listener.StreamWriting;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.implementation.Implementation.Context.Disabled.Factory;

/** Intercepts key JDK methods to provide a deterministic execution environment. */
public final class SimulatorAgent {
    public static final String AGENT_PROPERTY = "com.pingidentity.opendst.simulator.agent";

    /**
     * Installs bytecode transformation.
     *
     * @param agentArgs       Arguments for the agent, not used here.
     * @param instrumentation The instrumentation instance to use for transforming classes.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) throws IOException {
        // Force ThreadLocalRandom class initialization before the transformer is installed.
        // ConcurrentHashMap.fullAddCount() calls ThreadLocalRandom.getProbe(): if the *first* load
        // of ThreadLocalRandom happens inside fullAddCount() while the transformer is active, the
        // ByteBuddy transform path itself goes through ConcurrentHashMap and re-enters the load,
        // failing with ClassCircularityError (observed on the "VirtualThread-unblocker" thread).
        // Loading it here is safe: RedefinitionStrategy.REDEFINITION retransforms already-loaded
        // classes when installOn() runs below, so deterministic randomness still applies.
        ThreadLocalRandom.current();

        AgentBuilder agent = new Default()
                .disableClassFormatChanges()
                .enableNativeMethodPrefix("native")
                .with(new ByteBuddy().with(Factory.INSTANCE))
                .with(InitializationStrategy.NoOp.INSTANCE)
                .with(UsingReflection.INSTANCE)
                .with(RedefinitionStrategy.REDEFINITION)
                .with(TypeStrategy.Default.REDEFINE)
                .with(StreamWriting.toSystemError().withErrorsOnly())
                .ignore(not(nameStartsWith("java.")
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("javax."))));

        agent = TimeInterceptors.instrument(agent);
        agent = SystemInterceptors.instrument(agent);
        agent = RandomInterceptors.instrument(agent);
        agent = ThreadsInterceptors.instrument(agent);
        agent = StreamInterceptors.instrument(agent);
        agent = CompletableFutureInterceptors.instrument(agent);
        agent = ParallelComputeInterceptors.instrument(agent);
        agent = BigIntegerInterceptors.instrument(agent);
        agent = SubmissionPublisherInterceptors.instrument(agent);
        agent = NetworkInterceptors.instrument(agent);

        agent.installOn(instrumentation);

        installSimulatorThreadCallback();
        setProperty(AGENT_PROPERTY, "true");

        // Force ThreadsInterceptors.Internals class initialization while still single-threaded.
        // Its <clinit> calls MethodType.methodType() which goes through ConcurrentHashMap and
        // would otherwise run lazily on whichever thread first touches Internals — typically the
        // "VirtualThread-unblocker" thread racing the simulation thread (see ClassCircularityError
        // note above). Doing it here, before the warmup below starts the unblocker thread, makes
        // the initialization order deterministic.
        try {
            MethodHandles.lookup().ensureInitialized(ThreadsInterceptors.Internals.class);
        } catch (IllegalAccessException e) {
            throw new SimulationError("Unable to initialize ThreadsInterceptors.Internals", e);
        }

        // Force VirtualThread class initialization outside the simulation context.
        // VirtualThread.<clinit> starts the "VirtualThread-unblocker" platform thread
        // (an InnocuousThread). If this happens inside a simulation, the ThreadStartAdvice
        // would flag it as a platform thread escape — but it's already handled by
        // UnblockVirtualThreadInterceptor and is not a source of non-determinism.
        // Pre-initializing here ensures the unblocker thread exists before any simulation runs.
        Thread.ofVirtual().name("opendst-warmup").unstarted(() -> {});
    }

    private SimulatorAgent() {
        // Prevent instantiation
    }
}
