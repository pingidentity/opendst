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
package com.pingidentity.opendst.maven;

import static java.lang.Character.isDigit;
import static java.lang.Character.isLowerCase;
import static java.lang.Character.isUpperCase;
import static java.lang.Character.toLowerCase;
import static java.lang.Runtime.getRuntime;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newFixedThreadPool;

import com.pingidentity.opendst.common.RuntimeDeployment;
import com.pingidentity.opendst.common.RuntimeDeployment.RuntimeAuditor;
import com.pingidentity.opendst.common.RuntimeDeployment.RuntimeService;
import com.pingidentity.opendst.maven.BuildMojo.DeploymentConfiguration.SourceConfiguration;
import com.pingidentity.opendst.maven.ClasspathResolver.Classpath;
import com.pingidentity.opendst.maven.Instrumentation.AssertionValidationException;
import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * The {@code build} goal produces a self-contained executable JAR that runs an OpenDST simulation without Maven. All
 * bytecode instrumentation happens at build time.
 *
 * <p>The deployment topology is declared inline in the plugin {@code <configuration>} (a
 * {@code <deployment>} of {@code <services>}); with none declared, it falls back to zero-config
 * discovery. Usage: {@code mvn opendst:build}.
 *
 * <p>The produced JAR can then be run with: {@code java -jar target/<finalName>-opendst.jar}
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.TEST)
public class BuildMojo extends AbstractMojo {

    /** The simulation address space for zero-config mode. {@code 10.0.0.0} is the network address, so .1 up. */
    private static final int MAX_ZEROCONF_SERVICES = 254;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @SuppressWarnings("deprecation")
    @org.apache.maven.plugins.annotations.Component
    private RepositorySystem repositorySystem;

    @SuppressWarnings("deprecation")
    @org.apache.maven.plugins.annotations.Component
    private MavenProjectHelper projectHelper;

    /**
     * The deployment topology, declared inline in the plugin {@code <configuration>}. When omitted,
     * {@code opendst:build} falls back to zero-config discovery (scanning compiled classes for a
     * {@code main} method).
     */
    @Parameter
    private DeploymentConfiguration deployment;

    @Parameter(
            property = "opendst.outputJar",
            defaultValue = "${project.build.directory}/${project.build.finalName}-opendst.jar")
    private File outputJar;

    @Parameter(property = "opendst.jvmArguments")
    private String jvmArguments;

    @Parameter(property = "opendst.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping opendst:build because opendst.skip is enabled.");
            return;
        }

        buildOpendstJar();
        getLog().info("Built self-contained JAR " + outputJar.getName());
        getLog().info("Run with: java -jar " + outputJar.getName());

        projectHelper.attachArtifact(project, "jar", "opendst", outputJar);
    }

    /**
     * Translates a deployment configuration into the self-contained JAR: a declared {@code <deployment>}
     * (or, when absent, a zero-config scan) becomes a {@link RuntimeDeployment} whose apps are resolved,
     * instrumented, and sealed into the jar. {@return that runtime deployment}.
     *
     * <p>The three steps are the whole build: interpret the config (and gather the apps behind it),
     * instrument those apps into {@code apps/}, then write everything — instrumented apps, the OpenDST
     * {@link SystemJars runtime}, and the runtime manifest — with {@link OpendstJar}.
     */
    private void buildOpendstJar() throws MojoExecutionException, MojoFailureException {
        Path basePath;
        try {
            basePath = project.getBasedir().toPath().toRealPath();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to resolve base path", e);
        }
        // Interpret + resolve: the declared <deployment> (or a zero-config scan) becomes the runtime
        // deployment plus the distinct resolved apps behind its services, deduplicated by output dir.
        var config = deployment != null && !deployment.services().isEmpty() ? deployment : zeroConfig();
        var apps = new LinkedHashMap<String, Classpath>();
        var resolver = new ClasspathResolver(basePath, project, session, repositorySystem, getLog());
        var runtimeDeployment = translate(config, resolver, apps);

        // Build the jar: write the OpenDST runtime prologue, instrument each app straight into
        // apps/<outputDir>/WEB-INF, then seal in the runtime manifest and the assertion catalog.
        try (var executor = newFixedThreadPool(getRuntime().availableProcessors() * 2);
                var jar = new OpendstJar(outputJar.toPath(), executor)) {
            for (var classpath : apps.values()) {
                getLog().info("Instrumenting %s from %s".formatted(classpath.outputDir(), classpath));
                jar.addApp(classpath);
            }
            jar.seal(runtimeDeployment);
        } catch (AssertionValidationException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to build self-contained JAR", e);
        }
    }

    /**
     * Synthesizes a deployment config from the compiled classes when no {@code <deployment>} is declared: one
     * service per {@code main} class, named after it ({@code MyServer} → {@code my-server}) and addressed
     * {@code 10.0.0.1}, {@code .2}, … in scan order, plus the project's {@code TraceAuditor} if it has exactly
     * one. Every synthesized service is sourced from the current project's compile classes, so
     * {@link #translate} resolves them to one shared app. {@return that config}.
     */
    private DeploymentConfiguration zeroConfig() throws MojoFailureException {
        var classesDir = Path.of(project.getBuild().getOutputDirectory());
        List<String> mainClasses;
        List<String> traceAuditors;
        try {
            mainClasses = MainClassScanner.scan(classesDir);
            traceAuditors = MainClassScanner.scanTraceAuditor(classesDir);
        } catch (IOException e) {
            throw new MojoFailureException("Failed to scan compiled classes in " + classesDir, e);
        }

        if (mainClasses.isEmpty()) {
            throw new MojoFailureException("Zero-config mode: no class with 'public static void main(String[])' was"
                    + " found in " + classesDir + ". Add a main method to your class, or declare a <deployment> in"
                    + " the plugin configuration to define your services explicitly.");
        }
        if (mainClasses.size() > MAX_ZEROCONF_SERVICES) {
            throw new MojoFailureException("Zero-config mode: found " + mainClasses.size()
                    + " main classes; cannot assign IPs beyond 10.0.0." + MAX_ZEROCONF_SERVICES
                    + ". Declare a <deployment> in the plugin configuration to configure services explicitly.");
        }
        if (traceAuditors.size() > 1) {
            throw new MojoFailureException("Zero-config mode: more than one TraceAuditor implementor was found in "
                    + classesDir + ": " + traceAuditors
                    + ". Declare a <deployment> in the plugin configuration to specify the TraceAuditor explicitly.");
        }

        var services = new ArrayList<DeploymentConfiguration.ServiceConfiguration>();
        var byName = new HashMap<String, String>(); // service name -> className, for clash detection
        for (int i = 0; i < mainClasses.size(); i++) {
            var className = mainClasses.get(i);
            var name = MainClassScanner.toHostname(className.substring(className.lastIndexOf('.') + 1));
            var clash = byName.putIfAbsent(name, className);
            if (clash != null) {
                throw new MojoFailureException("Zero-config mode: two classes produce the same service name '" + name
                        + "': [" + clash + ", " + className + "]. Declare a <deployment> in the plugin"
                        + " configuration to assign distinct service names.");
            }
            services.add(DeploymentConfiguration.ServiceConfiguration.of(name, className, "10.0.0." + (i + 1)));
        }
        var auditor =
                traceAuditors.isEmpty() ? null : DeploymentConfiguration.TraceAuditor.of(traceAuditors.getFirst());
        return DeploymentConfiguration.of(services, auditor);
    }

    /**
     * Resolves {@code config} into the {@link RuntimeDeployment} baked into the JAR: each service's loose
     * {@code artifact}/{@code dir}/{@code scope} fields are resolved to a {@link Classpath}, filling
     * {@code apps} with the distinct ones (keyed and deduplicated by {@code outputDir} so a shared source is
     * instrumented once). {@return that runtime deployment}. The child only needs which {@code apps/}
     * directory a service's classes are in, never where they came from — so provenance is resolved here and
     * the runtime never hears it.
     */
    private RuntimeDeployment translate(
            DeploymentConfiguration config, ClasspathResolver classPathResolver, Map<String, Classpath> apps)
            throws MojoFailureException {
        var services = new LinkedHashMap<String, RuntimeService>();
        try {
            for (var service : config.services()) {
                if (service.name() == null || service.name().isBlank()) {
                    throw new MojoFailureException("Every <service> needs a <name>");
                }
                var cp = resolveSource(classPathResolver, service);
                apps.putIfAbsent(cp.outputDir(), cp);
                services.put(
                        service.name(),
                        new RuntimeService(
                                cp.outputDir(),
                                requireNonNull(service.className(), "<className>"),
                                requireNonNull(service.ip(), "<ip>"),
                                service.args()));
            }
            var declaredAuditor = config.traceAuditor();
            RuntimeAuditor auditor = null;
            if (declaredAuditor != null) {
                var cp = resolveSource(classPathResolver, declaredAuditor);
                apps.putIfAbsent(cp.outputDir(), cp);
                auditor =
                        new RuntimeAuditor(cp.outputDir(), requireNonNull(declaredAuditor.className(), "<className>"));
            }
            return new RuntimeDeployment(jvmArguments, services, auditor);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MojoFailureException("Invalid <deployment> configuration: " + e.getMessage(), e);
        } catch (IOException | ArtifactResolutionException e) {
            throw new MojoFailureException("Failed to resolve application source: " + e.getMessage(), e);
        }
    }

    /**
     * Selects the {@link ClasspathResolver} method matching a {@link SourceConfiguration
     * source}'s mutually-exclusive {@code artifact}/{@code dir}/{@code scope} selectors — the config-shape
     * switch, kept here in the config interpreter so {@link ClasspathResolver} exposes only intent-named,
     * single-kind resolve methods. {@return the resolved classpath}.
     *
     * @throws IllegalArgumentException if more than one selector is set, or the scope value is unrecognized
     */
    private static Classpath resolveSource(ClasspathResolver resolver, SourceConfiguration source)
            throws IOException, ArtifactResolutionException {
        var artifact = source.artifact();
        var dir = source.dir();
        var scope = source.scope();
        boolean hasArtifact = artifact != null && !artifact.isBlank();
        boolean hasDir = dir != null && !dir.isBlank();
        boolean hasScope = scope != null && !scope.isBlank();
        if ((hasArtifact ? 1 : 0) + (hasDir ? 1 : 0) + (hasScope ? 1 : 0) > 1) {
            throw new IllegalArgumentException(
                    "Only one of 'artifact', 'dir', or 'scope' can be set, but found multiple");
        }
        if (hasArtifact) {
            return resolver.resolveArtifact(artifact);
        }
        if (hasDir) {
            return resolver.resolveDirectory(dir);
        }
        if (!hasScope || "compile".equals(scope)) {
            return resolver.resolveCurrentProject();
        }
        if ("test".equals(scope)) {
            return resolver.resolveCurrentProjectTests();
        }
        throw new IllegalArgumentException(
                "Invalid scope '%s': valid values are 'compile' and 'test'".formatted(scope));
    }

    // ------------------------------------------------------------------
    // Configuration surface — what a user writes in the pom.
    //
    // These are mutable beans with no-arg constructors because that is the only shape Maven's
    // configurator can populate; zeroConfig() also builds them, and translate() turns them into a
    // resolved deployment (records + a sealed App) that the rest of the build reasons about.
    // ------------------------------------------------------------------

    /**
     * The deployment topology, declared inline in the plugin {@code <configuration>} — the pom-native
     * replacement for {@code deployment.yaml}. When absent, {@code opendst:build} falls back to
     * zero-config discovery (scanning the compiled classes for a {@code main} method).
     *
     * <pre>{@code
     * <deployment>
     *   <services>
     *     <service>
     *       <name>app</name>
     *       <className>com.example.MyApp</className>
     *       <ip>10.0.0.1</ip>
     *     </service>
     *   </services>
     * </deployment>
     * }</pre>
     */
    public static class DeploymentConfiguration {

        private List<ServiceConfiguration> services = new ArrayList<>();
        private TraceAuditor traceAuditor;

        public List<ServiceConfiguration> services() {
            return services;
        }

        public TraceAuditor traceAuditor() {
            return traceAuditor;
        }

        /**
         * Builds a deployment programmatically (zero-config {@link BuildMojo#zeroConfig() discovery}), bypassing
         * Maven's XML configurator. A no-arg constructor still exists for that configurator; this is the
         * code-side construction path.
         */
        private static DeploymentConfiguration of(List<ServiceConfiguration> services, TraceAuditor traceAuditor) {
            var deployment = new DeploymentConfiguration();
            deployment.services = services;
            deployment.traceAuditor = traceAuditor;
            return deployment;
        }

        /**
         * The mutually-exclusive source selectors a {@code <service>} and a {@code <traceAuditor>} share —
         * where its bytecode comes from ({@code <artifact>} / {@code <dir>} / {@code <scope>}, or none for the
         * current project's compile classes). {@link BuildMojo#resolveSource resolveSource} consumes exactly
         * this surface, blind to whether it is resolving a service or an auditor.
         */
        public interface SourceConfiguration {
            String artifact();

            String dir();

            String scope();
        }

        /**
         * One {@code <service>}: an application class and its network identity, plus where its bytecode
         * comes from.
         *
         * <p>At most one source may be given — {@code <artifact>} (a {@code groupId:artifactId:version}
         * coordinate, resolved from the repositories), {@code <dir>} (an already-built directory), or
         * {@code <scope>} ({@code compile}/{@code test} of the current project). With none, the current
         * project's compile classes are used.
         */
        public static class ServiceConfiguration implements SourceConfiguration {

            private String name;
            private String className;
            private String ip;
            private List<String> args = new ArrayList<>();
            private String dir;
            private String artifact;
            private String scope;

            /**
             * Builds a zero-config service: named, classed, and addressed, sourced from the current
             * project's compile classes (no {@code artifact}/{@code dir}/{@code scope}) with no args.
             */
            private static ServiceConfiguration of(String name, String className, String ip) {
                var service = new ServiceConfiguration();
                service.name = name;
                service.className = className;
                service.ip = ip;
                return service;
            }

            public String name() {
                return name;
            }

            public String className() {
                return className;
            }

            public String ip() {
                return ip;
            }

            public List<String> args() {
                return args;
            }

            @Override
            public String dir() {
                return dir;
            }

            @Override
            public String artifact() {
                return artifact;
            }

            @Override
            public String scope() {
                return scope;
            }
        }

        /**
         * The optional {@code <traceAuditor>}: a class judged over the run's trace, with the same source
         * model as a {@link ServiceConfiguration}.
         */
        public static class TraceAuditor implements SourceConfiguration {

            private String className;
            private String dir;
            private String artifact;
            private String scope;

            /** Builds a zero-config trace auditor sourced from the current project's compile classes. */
            private static TraceAuditor of(String className) {
                var auditor = new TraceAuditor();
                auditor.className = className;
                return auditor;
            }

            public String className() {
                return className;
            }

            @Override
            public String dir() {
                return dir;
            }

            @Override
            public String artifact() {
                return artifact;
            }

            @Override
            public String scope() {
                return scope;
            }
        }
    }

    /**
     * Scans compiled class files under a directory to discover the main-class candidates and
     * {@code TraceAuditor} implementor that {@link #zeroConfig() zero-config discovery} synthesizes services
     * from.
     *
     * <p>All scanning uses the Java ClassFile API ({@code java.lang.classfile}), consistent with existing
     * usage in {@link Instrumentation} and {@link Instrumentation.AssertionScanner}.
     */
    static final class MainClassScanner {

        private static final String TRACE_AUDITOR_INTERNAL_NAME = "com/pingidentity/opendst/sdk/TraceAuditor";

        private static final MethodTypeDesc MAIN_DESCRIPTOR = MethodTypeDesc.of(CD_void, CD_String.arrayType());

        private MainClassScanner() {}

        /**
         * Scans {@code .class} files under {@code classesDir} and returns the fully qualified class
         * names of every class that declares {@code public static void main(String[])}.
         *
         * <p>Results are sorted alphabetically by simple class name (the part after the last {@code .}).
         *
         * @param classesDir the root directory to scan (e.g. {@code target/classes})
         * @return an immutable list of FQCNs, sorted by simple name; empty if none found
         * @throws IOException if reading any {@code .class} file fails
         */
        static List<String> scan(Path classesDir) throws IOException {
            return walkClasses(classesDir, model -> model.methods().stream()
                    .anyMatch(m -> m.methodName().stringValue().equals("main")
                            && m.flags().has(AccessFlag.PUBLIC)
                            && m.flags().has(AccessFlag.STATIC)
                            && m.methodTypeSymbol().equals(MAIN_DESCRIPTOR)));
        }

        /**
         * Scans {@code .class} files under {@code classesDir} and returns the fully qualified class
         * names of every class whose direct interface list includes
         * {@code com.pingidentity.opendst.sdk.TraceAuditor}.
         *
         * <p>Results are sorted alphabetically by simple class name for deterministic diagnostic output.
         *
         * @param classesDir the root directory to scan (e.g. {@code target/classes})
         * @return an immutable list of FQCNs, sorted by simple name; empty if none found
         * @throws IOException if reading any {@code .class} file fails
         */
        static List<String> scanTraceAuditor(Path classesDir) throws IOException {
            return walkClasses(classesDir, model -> model.interfaces().stream()
                    .anyMatch(i -> i.asInternalName().equals(TRACE_AUDITOR_INTERNAL_NAME)));
        }

        /**
         * Walks {@code .class} files under {@code classesDir}, parses each with the ClassFile API,
         * and returns the FQCNs of classes matching {@code predicate}, sorted by simple name.
         */
        private static List<String> walkClasses(Path classesDir, Predicate<ClassModel> predicate) throws IOException {
            var cf = ClassFile.of();
            var candidates = new ArrayList<String>();
            try (var stream = walk(classesDir)) {
                for (var it = stream.iterator(); it.hasNext(); ) {
                    var path = it.next();
                    if (!path.toString().endsWith(".class")) {
                        continue;
                    }
                    var model = cf.parse(readAllBytes(path));
                    if (predicate.test(model)) {
                        candidates.add(model.thisClass().asInternalName().replace('/', '.'));
                    }
                }
            }
            candidates.sort(Comparator.comparing(MainClassScanner::simpleName));
            return List.copyOf(candidates);
        }

        /**
         * Converts a simple class name to a hostname-safe service name.
         *
         * <ul>
         *   <li>CamelCase word boundaries become {@code -}: {@code MyServer} → {@code my-server}.</li>
         *   <li>Acronym runs are kept together: {@code FlakyDST} → {@code flaky-dst},
         *       {@code MyDSTServer} → {@code my-dst-server}.</li>
         *   <li>Digits are passed through and treated as word-boundary triggers for the next
         *       uppercase letter: {@code Http2Client} → {@code http2-client}.</li>
         *   <li>Underscores become {@code .} (subdomain separator): {@code My_Server} →
         *       {@code my.server}.</li>
         * </ul>
         *
         * @param simpleClassName the simple (unqualified) class name to convert
         * @return the hostname-safe service name
         */
        static String toHostname(String simpleClassName) {
            var sb = new StringBuilder();
            int len = simpleClassName.length();
            for (int i = 0; i < len; i++) {
                char c = simpleClassName.charAt(i);
                if (c == '_') {
                    sb.append('.');
                    continue;
                }
                if (isUpperCase(c) && i > 0) {
                    char prev = simpleClassName.charAt(i - 1);
                    char next = i + 1 < len ? simpleClassName.charAt(i + 1) : 0;
                    // Insert hyphen at word boundaries:
                    // - after a lowercase letter or digit (Http2Client → http2-client)
                    // - at the end of an acronym run before a new word (DSTServer → dst-server)
                    if (isLowerCase(prev) || isDigit(prev) || (isUpperCase(prev) && isLowerCase(next))) {
                        sb.append('-');
                    }
                }
                sb.append(toLowerCase(c));
            }
            return sb.toString();
        }

        /** Returns the simple name portion of a fully qualified class name. */
        private static String simpleName(String fqcn) {
            int dot = fqcn.lastIndexOf('.');
            return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
        }
    }
}
