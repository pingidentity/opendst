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
package com.pingidentity.opendst.common;

import java.util.List;
import java.util.Map;

/**
 * Runtime view of a deployment, baked into the self-contained JAR by the build plugin and read by the child JVM at
 * startup (and by the parent runner to assemble the child JVM command line).
 *
 * <p>This is the post-enrichment, runtime-only counterpart to {@code DeploymentDescriptor} (in {@code opendst-maven
 * -plugin}). Where the descriptor carries build-time concerns (sealed {@code Source} hierarchy with {@code artifact}
 * /{@code dir}/{@code scope} variants, Maven project metadata, custom Jackson factories), {@code RuntimeDeployment}
 * contains only what the runner and launcher need at startup: the JVM argument string assembled by the build plugin
 * and per-service {@code dir}/{@code className}/{@code ip}/{@code args} tuples plus an optional trace auditor.
 *
 * <p>The wire format is plain JSON (no annotations, no polymorphism) so it can be parsed by {@code jackson-jr} on
 * the child side without dragging {@code jackson-databind} or {@code jackson-yaml} into the agent JAR.
 *
 * <p>The build plugin produces this from its own deployment model once every service has been instrumented:
 * where the bytecode originally came from is a build-time concern, and does not survive into the JAR — only the
 * {@code apps/} subdirectory it was written to.
 */
public record RuntimeDeployment(
        String jvmArguments, Map<String, RuntimeService> services, RuntimeAuditor traceAuditor) {

    /**
     * One service's runtime view.
     *
     * @param dir       the {@code apps/} subdirectory containing this service's instrumented classes
     * @param className the fully qualified name of the service's main class
     * @param ip        the dotted-quad IPv4 address assigned to this service in the simulation
     * @param args      the {@code String[]} arguments passed to {@code main}
     */
    public record RuntimeService(String dir, String className, String ip, List<String> args) {

        public RuntimeService {
            args = args == null ? List.of() : List.copyOf(args);
        }

        /** Returns {@link #args} as a {@code String[]} suitable for passing to {@code main(String[])}. */
        public String[] argsArray() {
            return args.toArray(String[]::new);
        }
    }

    /**
     * Trace auditor configuration, or {@code null} if no auditor is configured.
     *
     * @param dir       the {@code apps/} subdirectory containing the auditor's classes
     * @param className the fully qualified name of the {@code TraceAuditor} implementation
     */
    public record RuntimeAuditor(String dir, String className) {}
}
