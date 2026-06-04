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
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;

/**
 * Functional module forcing {@link BigInteger}'s parallel multiply/square to run sequentially.
 *
 * <p>For large operands {@code BigInteger.multiply}/{@code square} (and the public
 * {@code parallelMultiply}) fork onto the shared {@code ForkJoinPool} common pool via
 * {@code BigInteger$RecursiveOp}, running on platform workers that escape the deterministic
 * per-node scheduler. Both route through an internal {@code parallel} flag — {@code
 * multiply(BigInteger, boolean isRecursion, boolean parallel, int depth)} and {@code
 * square(boolean isRecursion, boolean parallel, int depth)}. Forcing that flag {@code false} inside
 * a node keeps the computation on the calling thread; the result is identical. No-op outside a
 * simulation.
 */
public final class BigIntegerInterceptors {

    /** Forces the {@code parallel} flag (arg 2) of {@code BigInteger#multiply(BigInteger,boolean,boolean,int)}. */
    @Intercepts(
            value = "java.math.BigInteger#multiply(BigInteger,boolean,boolean,int)",
            comment = "Large parallel multiply forks BigInteger$RecursiveOp onto the common pool; force "
                    + "sequential so it stays on the deterministic per-node scheduler.")
    public static final class MultiplyAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 2, readOnly = false) boolean parallel) {
            if (parallel && currentNodeOrNull() != null) {
                parallel = false;
            }
        }
    }

    /** Forces the {@code parallel} flag (arg 1) of {@code BigInteger#square(boolean,boolean,int)}. */
    @Intercepts(
            value = "java.math.BigInteger#square(boolean,boolean,int)",
            comment = "Large parallel square forks BigInteger$RecursiveOp onto the common pool; force "
                    + "sequential so it stays on the deterministic per-node scheduler.")
    public static final class SquareAdvice {
        @OnMethodEnter
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onEnter(@Argument(value = 1, readOnly = false) boolean parallel) {
            if (parallel && currentNodeOrNull() != null) {
                parallel = false;
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        // Match by argument shape rather than by referencing BigInteger.class: loading
        // BigInteger.class while transforming BigInteger itself throws ClassCircularityError.
        // multiply(BigInteger, boolean, boolean, int) is the only 4-arg "multiply" with this shape.
        return agent.type(named("java.math.BigInteger"))
                .transform((builder, _, _, _, _) -> builder.visit(to(MultiplyAdvice.class)
                                .on(named("multiply")
                                        .and(takesArguments(4))
                                        .and(takesArgument(1, boolean.class))
                                        .and(takesArgument(2, boolean.class))
                                        .and(takesArgument(3, int.class))))
                        .visit(to(SquareAdvice.class)
                                .on(named("square").and(takesArguments(boolean.class, boolean.class, int.class)))))
                .asTerminalTransformation();
    }

    private BigIntegerInterceptors() {
        // Prevent instantiation
    }
}
