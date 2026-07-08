/*
 * Copyright 2024-2026 Ping Identity Corporation
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

import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.pingidentity.opendst.simulator.Node;
import com.pingidentity.opendst.simulator.Simulator.SystemExitError;
import java.io.PrintStream;
import java.lang.ref.Reference;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.asm.Advice.Return;

/** Advices and instrumentation for simulated system methods (ReferenceQueue, Runtime, etc.). */
public final class SystemInterceptors {
    private SystemInterceptors() {
        // Prevent instantiation
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent
                /** {@link java.lang.ref.ReferenceQueue} */
                .type(named("java.lang.ref.ReferenceQueue"))
                .transform((builder, _, _, _, _) -> builder.visit(
                                to(ReferenceQueuePollAdvice.class).on(named("poll")))
                        .visit(to(ReferenceQueueRemoveAdvice.class)
                                .on(named("remove").and(takesNoArguments())))
                        .visit(to(ReferenceQueueRemoveTimeoutAdvice.class)
                                .on(named("remove").and(takesArguments(long.class)))))
                /** {@link jdk.jfr.FlightRecorder#addPeriodicEvent(Class, Runnable)} */
                .type(named("jdk.jfr.FlightRecorder"))
                .transform((builder, _, _, _, _) -> builder.visit(
                        to(FlightRecorderAddPeriodicEventAdvice.class).on(named("addPeriodicEvent"))))
                .asTerminalTransformation()
                /** {@link Runtime#addShutdownHook(Thread)} and {@link Runtime#removeShutdownHook(Thread)} */
                .type(named("java.lang.Runtime"))
                .transform((builder, _, _, _, _) -> builder.visit(
                                to(RuntimeAddShutdownHookAdvice.class).on(named("addShutdownHook")))
                        .visit(to(RuntimeRemoveShutdownHookAdvice.class).on(named("removeShutdownHook")))
                        .visit(to(RuntimeExitAdvice.class).on(named("exit"))))
                .asTerminalTransformation()
                /** {@link System#setOut(PrintStream)} and {@link System#setErr(PrintStream)} */
                .type(named("java.lang.System"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(ConsoleAdvice.class).on(named("setOut").or(named("setErr")))))
                .asTerminalTransformation()
                /** {@link ProcessHandle#pid()} — returns real OS PID, which varies per JVM invocation */
                .type(named("java.lang.ProcessHandleImpl"))
                .transform((builder, _, _, _, _) ->
                        builder.visit(to(ProcessHandlePidAdvice.class).on(named("pid"))))
                .asTerminalTransformation();
    }

    /** Overrides {@link java.lang.ref.ReferenceQueue#poll()} }. */
    @Intercepts("java.lang.ref.ReferenceQueue#poll()")
    public static final class ReferenceQueuePollAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Node node, @Return(readOnly = false) Reference<?> object) {
            if (node != null) {
                object = null;
            }
        }
    }

    /** Overrides {@link java.lang.ref.ReferenceQueue#remove()} }. */
    @Intercepts("java.lang.ref.ReferenceQueue#remove()")
    public static final class ReferenceQueueRemoveAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Node node, @Return(readOnly = false) Reference<?> object)
                throws InterruptedException {
            if (node != null) {
                new Semaphore(0).acquire();
            }
        }
    }

    /** Overrides {@link java.lang.ref.ReferenceQueue#remove(long)} }. */
    @Intercepts("java.lang.ref.ReferenceQueue#remove(long)")
    public static final class ReferenceQueueRemoveTimeoutAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(
                @Enter Node node, @Argument(value = 0) long timeout, @Return(readOnly = false) Reference<?> object)
                throws InterruptedException {
            if (node != null) {
                if (timeout == 0) {
                    new Semaphore(0).acquire();
                } else {
                    new Semaphore(0).tryAcquire(timeout, TimeUnit.MILLISECONDS);
                    object = null;
                }
            }
        }
    }

    /** Overrides {@link jdk.jfr.FlightRecorder#addPeriodicEvent(Class, Runnable)}. */
    @Intercepts(
            value = "jdk.jfr.FlightRecorder#addPeriodicEvent(Class,Runnable)",
            noOp = true,
            comment =
                    "JFR is really low-level but maybe the periodicity aspect could be implemented on top of the run loop")
    public static final class FlightRecorderAddPeriodicEventAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }
    }

    /** Overrides {@link Runtime#addShutdownHook(Thread)}. */
    @Intercepts("java.lang.Runtime#addShutdownHook(Thread)")
    public static final class RuntimeAddShutdownHookAdvice {
        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodExit
        public static void onExit(@Enter Node node, @Argument(value = 0, readOnly = true) Thread hook) {
            if (node != null) {
                node.addShutdownHook(hook);
            }
        }
    }

    /** Overrides {@link Runtime#removeShutdownHook(Thread)}. */
    @Intercepts("java.lang.Runtime#removeShutdownHook(Thread)")
    public static final class RuntimeRemoveShutdownHookAdvice {
        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodExit
        public static void onExit(
                @Enter Node node,
                @Argument(value = 0, readOnly = true) Thread hook,
                @Return(readOnly = false) boolean removed) {
            if (node != null) {
                removed = node.removeShutdownHook(hook);
            }
        }
    }

    /** Overrides {@link Runtime#exit(int)}. */
    @Intercepts("java.lang.Runtime#exit(int)")
    public static final class RuntimeExitAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Node node, @Argument(value = 0) int exitCode) {
            if (node != null) {
                node.exit(exitCode);
                throw new SystemExitError(exitCode);
            }
        }
    }

    /** Intercepts {@link System#setOut(PrintStream)} and {@link System#setErr(PrintStream)}. */
    @Intercepts("java.lang.System#setOut(PrintStream)")
    @Intercepts("java.lang.System#setErr(PrintStream)")
    public static final class ConsoleAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }
    }

    /**
     * Overrides {@link ProcessHandle#pid()} to return a fixed value inside simulation nodes.
     * The real OS PID changes on every JVM invocation, producing a non-deterministic signal
     * in any log message that includes the PID (e.g. DS's "JVM Information: PID(NNNNN)...").
     *
     * <p>Could be removed if/when each simulation run is isolated in its own container (Docker or
     * similar), since the PID could then be pinned at the OS level instead of intercepted here.
     */
    @Intercepts("java.lang.ProcessHandle#pid()")
    public static final class ProcessHandlePidAdvice {
        /** Deterministic PID returned inside simulation nodes. */
        private static final long SIMULATED_PID = 1L;

        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return Node.currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ReassignedVariable", "ParameterCanBeLocal", "UnusedAssignment"})
        public static void onExit(@Enter Node node, @Return(readOnly = false) long pid) {
            if (node != null) {
                pid = SIMULATED_PID;
            }
        }
    }
}
