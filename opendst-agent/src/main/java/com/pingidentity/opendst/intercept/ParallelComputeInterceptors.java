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
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.pingidentity.opendst.simulator.Node;
import java.util.Arrays;
import java.util.Comparator;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;

/**
 * Functional module forcing common-pool-backed compute APIs to run sequentially under simulation.
 *
 * <p>These APIs would otherwise fork onto the shared {@code ForkJoinPool} common pool, running on
 * platform {@code ForkJoinWorkerThread}s that escape the deterministic per-node scheduler. The pool
 * itself is not instrumented (it is used by the bytecode-redefinition machinery), so each API class
 * is intercepted directly to keep work on the calling node thread. Result-equivalent for the
 * well-behaved operations these APIs require; no-ops outside a simulation.
 *
 * <p>Covered here: every {@code Arrays.parallelSort} overload (redirected to the sequential
 * {@code Arrays.sort}), and {@code ConcurrentHashMap} bulk operations (threshold forced so they run
 * in the caller). {@code Arrays.parallelSetAll} is already covered by {@code StreamInterceptors}
 * (it is implemented with a parallel {@code IntStream}). {@code Arrays.parallelPrefix} is not yet
 * redirected (it has no sequential JDK equivalent and is rare); it is left to the platform-thread
 * escape detector as a follow-up.
 */
public final class ParallelComputeInterceptors {

    // ---- Arrays.parallelSort: redirect each overload to the sequential Arrays.sort ----

    /** {@code parallelSort(byte[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(byte[])", comment = "Redirect to sequential sort.")
    public static final class SortByteAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) byte[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(byte[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(byte[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortByteRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) byte[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(char[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(char[])", comment = "Redirect to sequential sort.")
    public static final class SortCharAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) char[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(char[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(char[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortCharRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) char[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(short[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(short[])", comment = "Redirect to sequential sort.")
    public static final class SortShortAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) short[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(short[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(short[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortShortRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) short[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(int[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(int[])", comment = "Redirect to sequential sort.")
    public static final class SortIntAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) int[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(int[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(int[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortIntRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) int[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(long[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(long[])", comment = "Redirect to sequential sort.")
    public static final class SortLongAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) long[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(long[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(long[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortLongRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) long[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(float[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(float[])", comment = "Redirect to sequential sort.")
    public static final class SortFloatAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) float[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(float[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(float[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortFloatRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) float[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(double[])}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(double[])", comment = "Redirect to sequential sort.")
    public static final class SortDoubleAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) double[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(double[], int, int)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(double[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortDoubleRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) double[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(T[])} (Comparable elements). */
    @Intercepts(value = "java.util.Arrays#parallelSort(Object[])", comment = "Redirect to sequential sort.")
    public static final class SortObjectAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) Object[] a) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a);
            }
            return node;
        }
    }

    /** {@code parallelSort(T[], int, int)} (Comparable elements). */
    @Intercepts(value = "java.util.Arrays#parallelSort(Object[],int,int)", comment = "Redirect to sequential sort.")
    public static final class SortObjectRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@Argument(0) Object[] a, @Argument(1) int from, @Argument(2) int to) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to);
            }
            return node;
        }
    }

    /** {@code parallelSort(T[], Comparator)}. */
    @Intercepts(value = "java.util.Arrays#parallelSort(Object[],Comparator)", comment = "Redirect to sequential sort.")
    public static final class SortComparatorAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings({"MissingJavadocMethod", "unchecked", "rawtypes"})
        public static Node onEnter(@Argument(0) Object[] a, @Argument(1) Comparator c) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, c);
            }
            return node;
        }
    }

    /** {@code parallelSort(T[], int, int, Comparator)}. */
    @Intercepts(
            value = "java.util.Arrays#parallelSort(Object[],int,int,Comparator)",
            comment = "Redirect to sequential sort.")
    public static final class SortComparatorRangeAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings({"MissingJavadocMethod", "unchecked", "rawtypes"})
        public static Node onEnter(
                @Argument(0) Object[] a, @Argument(1) int from, @Argument(2) int to, @Argument(3) Comparator c) {
            Node node = currentNodeOrNull();
            if (node != null) {
                Arrays.sort(a, from, to, c);
            }
            return node;
        }
    }

    // ---- ConcurrentHashMap bulk operations: force sequential (run in the caller) ----

    /**
     * Forces the {@code parallelismThreshold} (first {@code long} argument) of {@code
     * ConcurrentHashMap} bulk operations ({@code forEach*}/{@code reduce*}/{@code search*}) to
     * {@code Long.MAX_VALUE} inside a node, so {@code batchFor()} returns {@code 0} (no split — run
     * in the calling thread) and the work stays on the node's deterministic timeline.
     */
    @Intercepts(
            value = "java.util.concurrent.ConcurrentHashMap#forEach*/reduce*/search*(long,..)",
            comment = "Bulk ops fork onto the common pool above the parallelism threshold; forcing the "
                    + "threshold to Long.MAX_VALUE makes batchFor() return 0 (run in the caller).")
    public static final class BulkThresholdAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 0, readOnly = false) long parallelismThreshold) {
            if (currentNodeOrNull() != null) {
                parallelismThreshold = Long.MAX_VALUE;
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(named("java.util.Arrays"))
                .transform((builder, _, _, _, _) -> builder.visit(to(SortByteAdvice.class)
                                .on(named("parallelSort").and(takesArguments(byte[].class))))
                        .visit(to(SortByteRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(byte[].class, int.class, int.class))))
                        .visit(to(SortCharAdvice.class).on(named("parallelSort").and(takesArguments(char[].class))))
                        .visit(to(SortCharRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(char[].class, int.class, int.class))))
                        .visit(to(SortShortAdvice.class)
                                .on(named("parallelSort").and(takesArguments(short[].class))))
                        .visit(to(SortShortRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(short[].class, int.class, int.class))))
                        .visit(to(SortIntAdvice.class).on(named("parallelSort").and(takesArguments(int[].class))))
                        .visit(to(SortIntRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(int[].class, int.class, int.class))))
                        .visit(to(SortLongAdvice.class).on(named("parallelSort").and(takesArguments(long[].class))))
                        .visit(to(SortLongRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(long[].class, int.class, int.class))))
                        .visit(to(SortFloatAdvice.class)
                                .on(named("parallelSort").and(takesArguments(float[].class))))
                        .visit(to(SortFloatRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(float[].class, int.class, int.class))))
                        .visit(to(SortDoubleAdvice.class)
                                .on(named("parallelSort").and(takesArguments(double[].class))))
                        .visit(to(SortDoubleRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(double[].class, int.class, int.class))))
                        .visit(to(SortObjectAdvice.class)
                                .on(named("parallelSort").and(takesArguments(Object[].class))))
                        .visit(to(SortObjectRangeAdvice.class)
                                .on(named("parallelSort").and(takesArguments(Object[].class, int.class, int.class))))
                        .visit(to(SortComparatorAdvice.class)
                                .on(named("parallelSort").and(takesArguments(Object[].class, Comparator.class))))
                        .visit(to(SortComparatorRangeAdvice.class)
                                .on(named("parallelSort")
                                        .and(takesArguments(Object[].class, int.class, int.class, Comparator.class)))))
                .asTerminalTransformation()
                .type(named("java.util.concurrent.ConcurrentHashMap"))
                .transform((builder, _, _, _, _) -> builder.visit(to(BulkThresholdAdvice.class)
                        .on(takesArgument(0, long.class)
                                .and(nameStartsWith("forEach")
                                        .or(nameStartsWith("reduce"))
                                        .or(nameStartsWith("search"))))))
                .asTerminalTransformation();
    }

    private ParallelComputeInterceptors() {
        // Prevent instantiation
    }
}
