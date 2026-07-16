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

import static com.pingidentity.opendst.maven.OpendstJar.OpendstPatchJar.addOpendstPatchJar;
import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.nio.file.Files.write;
import static java.nio.file.Files.writeString;
import static java.util.Comparator.reverseOrder;
import static java.util.List.of;
import static java.util.zip.Deflater.DEFAULT_COMPRESSION;
import static java.util.zip.Deflater.NO_COMPRESSION;
import static javax.tools.ToolProvider.getSystemJavaCompiler;
import static tools.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static tools.jackson.databind.cfg.EnumFeature.WRITE_ENUMS_USING_TO_STRING;

import com.pingidentity.opendst.common.Assertion;
import com.pingidentity.opendst.common.RuntimeDeployment;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipInputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives construction of the self-contained executable jar — the deliverable of {@code opendst:build}.
 * Opening one lays down the fixed prologue (manifest, {@code Bootstrap.class}, the runtime jars, all written
 * straight in with no on-disk staging); {@link #addApp} instruments each app straight into it;
 * {@link #seal} writes the run's metadata. The result boots straight into {@code Bootstrap} under
 * {@code java -jar}:
 *
 * <pre>
 * META-INF/
 *   MANIFEST.MF                     # Main-Class: Bootstrap
 *   opendst/
 *     assertions.json               # Serialized Set&lt;Assertion&gt; (the catalog)
 *     deployment.json               # The deployment as the child JVM reads it
 * com/pingidentity/opendst/runner/Bootstrap.class  # Bootstrap (the only class at the root)
 * system/
 *   opendst-agent.jar / opendst-runner.jar / opendst-patch.jar
 * apps/
 *   &lt;outputDir&gt;/WEB-INF/{classes/, lib/}          # Instrumented application content
 * </pre>
 *
 * <p>Instrumented bytes stream directly in, so there is no on-disk staging tree to copy back.
 */
final class OpendstJar implements Closeable {

    private static final String BOOTSTRAP_CLASS_NAME = "com.pingidentity.opendst.runner.Bootstrap";
    private static final String AGENT_RESOURCE = "/META-INF/agents/opendst-agent.jar";
    private static final String RUNNER_RESOURCE = "/META-INF/agents/opendst-runner.jar";

    private static final String AGENT_JAR_ENTRY = "system/opendst-agent.jar";
    private static final String RUNNER_JAR_ENTRY = "system/opendst-runner.jar";
    private static final String PATCH_JAR_ENTRY = "system/opendst-patch.jar";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .disable(FAIL_ON_TRAILING_TOKENS)
            .disable(FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(AUTO_CLOSE_SOURCE)
            .enable(WRITE_ENUMS_USING_TO_STRING)
            .build();

    private final JarOutputStream jos;
    private final Instrumentation instrumentation;
    private final Set<Assertion> assertions = Instrumentation.newAssertionSet();

    /**
     * Opens the jar at {@code outputJar} and writes the fixed prologue: the manifest, {@code Bootstrap.class},
     * and the three OpenDST runtime jars under {@code system/}. The agent and runner are streamed straight
     * from this plugin's own classpath and the module patch is generated in memory
     * ({@link OpendstPatchJar}) — none of the runtime jars is staged on disk. {@code executor} backs the
     * transforms {@link #addApp} runs.
     */
    OpendstJar(Path outputJar, ExecutorService executor) throws IOException {
        createDirectories(outputJar.getParent());
        this.jos = new JarOutputStream(newOutputStream(outputJar));
        this.instrumentation = new Instrumentation(executor);

        addManifest();
        addBootstrapClass();
        addSystemJars();
    }

    /** Copies {@code entryPath} out of the jar at plugin classpath resource {@code resource} straight into
     * the jar under the same path. */
    private void addBootstrapClass() throws IOException {
        var bootstrapRelPath = BOOTSTRAP_CLASS_NAME.replace('.', '/') + ".class";
        try (var zis = new ZipInputStream(openResource(RUNNER_RESOURCE))) {
            for (var entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                if (entry.getName().equals(bootstrapRelPath)) {
                    putEntry(bootstrapRelPath);
                    zis.transferTo(jos);
                    jos.closeEntry();
                    return;
                }
            }
        }
        throw new IOException("%s not found in %s".formatted(bootstrapRelPath, RUNNER_RESOURCE));
    }

    /**
     * Writes the three OpenDST runtime jars under {@code system/} — the jars Bootstrap's
     * {@link java.net.URLClassLoader} loads after extraction. The agent and runner are streamed from this
     * plugin's own classpath; the module patch is generated straight in ({@link OpendstPatchJar}).
     */
    private void addSystemJars() throws IOException {
        addResource(AGENT_JAR_ENTRY, AGENT_RESOURCE);
        addResource(RUNNER_JAR_ENTRY, RUNNER_RESOURCE);
        addOpendstPatchJar(jos);
    }

    /**
     * Instruments {@code classpath} and streams the result straight into
     * {@code apps/<classpath.outputDir()>/WEB-INF/…}, accumulating the assertions it discovers into the
     * catalog {@link #seal} writes.
     */
    void addApp(ClasspathResolver.Classpath classpath) throws IOException {
        var appDir = "apps/%s/".formatted(classpath.outputDir());
        assertions.addAll(instrumentation.instrument(classpath, (name, content) -> addEntry(appDir + name, content)));
    }

    /** Writes the run's metadata last — the runtime deployment and the accumulated assertion catalog. */
    void seal(RuntimeDeployment deployment) throws IOException {
        addEntry("META-INF/opendst/deployment.json", JSON_MAPPER.writeValueAsBytes(deployment));
        addEntry("META-INF/opendst/assertions.json", JSON_MAPPER.writeValueAsBytes(assertions));
    }

    @Override
    public void close() throws IOException {
        jos.close();
    }

    /** Writes the jar manifest ({@code Main-Class: Bootstrap}) — the entry {@code java -jar} reads to boot. */
    private void addManifest() throws IOException {
        var manifest = new Manifest();
        var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.put(Attributes.Name.MAIN_CLASS, BOOTSTRAP_CLASS_NAME);
        putEntry(JarFile.MANIFEST_NAME);
        manifest.write(jos);
        jos.closeEntry();
    }

    private void addEntry(String name, byte[] content) throws IOException {
        putEntry(name);
        jos.write(content);
        jos.closeEntry();
    }

    /**
     * Opens a jar entry, storing already-compressed nested jars ({@code .jar} entries, at
     * {@link java.util.zip.Deflater#NO_COMPRESSION}) so they are not deflated a second time, and deflating
     * everything else. Level-0 deflate still streams (no CRC needed up front), so this keeps the
     * write-straight-through path.
     */
    private void putEntry(String name) throws IOException {
        jos.setLevel(name.endsWith(".jar") ? NO_COMPRESSION : DEFAULT_COMPRESSION);
        jos.putNextEntry(new JarEntry(name));
    }

    /** Copies the plugin classpath resource {@code resource} straight into the jar as entry {@code name}. */
    private void addResource(String name, String resource) throws IOException {
        try (var is = openResource(resource)) {
            putEntry(name);
            is.transferTo(jos);
            jos.closeEntry();
        }
    }

    /** {@return an open stream over the plugin classpath resource {@code resource}} (never {@code null}). */
    private static InputStream openResource(String resource) throws IOException {
        var is = OpendstJar.class.getResourceAsStream(resource);
        if (is == null) {
            throw new IOException("Could not find embedded resource: " + resource);
        }
        return is;
    }

    /**
     * Generates the {@code opendst-patch.jar} containing:
     * <ol>
     *   <li>{@code java/lang/VirtualThread.class} — with {@code ACC_FINAL} stripped</li>
     *   <li>{@code java/lang/SimulatorThread.class} — compiled from source, extends non-final
     *       {@code VirtualThread}, mirrors all {@code Thread} constructors</li>
     * </ol>
     *
     * <p>This JAR is used with {@code --patch-module java.base=opendst-patch.jar} to allow Thread subclasses
     * (rewritten to extend {@code SimulatorThread}) to run as virtual threads under the deterministic
     * simulator.
     *
     * <p><strong>JDK version constraint:</strong> the patched {@code VirtualThread.class} is read
     * byte-for-byte from the build JDK's runtime image ({@code jrt:/}), and {@code SimulatorThread} is
     * compiled against it, so the generated JAR is tied to the exact JDK version used during the build.
     */
    static final class OpendstPatchJar {

        /** MANIFEST attribute recording the JDK version used at build time. */
        static final String BUILD_JDK_VERSION_ATTR = "Build-Jdk-Version";

        /** Classpath resource containing the SimulatorThread source. */
        private static final String SIMULATOR_THREAD_SOURCE = "com/pingidentity/opendst/maven/SimulatorThread.java";

        /** Classpath resource containing the SimulatorTimer source. */
        private static final String SIMULATOR_TIMER_SOURCE = "com/pingidentity/opendst/maven/SimulatorTimer.java";

        private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");

        /** Timer's public constructor we clone: {@code Timer(String name, boolean isDaemon)}. */
        private static final MethodTypeDesc TIMER_CTOR = MethodTypeDesc.of(CD_void, CD_String, CD_boolean);

        /** The synthetic no-start ctor we add; the trailing int distinguishes it from {@link #TIMER_CTOR}. */
        private static final MethodTypeDesc TIMER_NO_START_CTOR =
                MethodTypeDesc.of(CD_void, CD_String, CD_boolean, CD_int);

        private OpendstPatchJar() {}

        /**
         * Generates the {@code opendst-patch.jar} and writes it as the entry {@code entryName} straight into
         * {@code jar}, with a {@code Build-Jdk-Version} manifest attribute recording the JDK runtime version
         * used during the build. A temporary directory holds the intermediate patched classes only for as
         * long as the in-process {@code javac} needs them on disk to compile the patch sources against; the
         * patch jar itself is streamed into {@code jar} without ever being buffered whole in memory.
         */
        static void addOpendstPatchJar(JarOutputStream jar) throws IOException {
            var tempDir = createTempDirectory("opendst-patch");
            try {
                // 1. Patch VirtualThread: strip ACC_FINAL and write to temp dir
                var patchedVT = patchVirtualThread();
                createDirectories(tempDir.resolve("java/lang"));
                write(tempDir.resolve("java/lang/VirtualThread.class"), patchedVT);

                // 2. Patch Timer: add a no-start ctor so SimulatorTimer can compile against it
                var patchedTimer = patchTimer();
                createDirectories(tempDir.resolve("java/util"));
                write(tempDir.resolve("java/util/Timer.class"), patchedTimer);

                // 3. Compile SimulatorThread.java against the patched VirtualThread
                var compiledST = compilePatchSource(tempDir, SIMULATOR_THREAD_SOURCE, "java/lang/SimulatorThread.java");

                // 4. Compile SimulatorTimer.java against the patched Timer + SimulatorThread
                createDirectories(tempDir.resolve("java/lang"));
                write(tempDir.resolve("java/lang/SimulatorThread.class"), compiledST);
                var compiledSimulatorTimer =
                        compilePatchSource(tempDir, SIMULATOR_TIMER_SOURCE, "java/util/SimulatorTimer.java");

                // 5. Stream a self-contained patch jar straight into `jar` as one nested entry. The nested
                //    JarOutputStream wraps `jar`, so it MUST be finish()ed and never close()d — close()
                //    would close `jar` and truncate the whole opendst.jar.
                var manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes()
                        .putValue(BUILD_JDK_VERSION_ATTR, Runtime.version().toString());
                // The patch jar is already compressed by the nested stream; store it (no second deflate).
                jar.setLevel(NO_COMPRESSION);
                jar.putNextEntry(new JarEntry(PATCH_JAR_ENTRY));
                var nested = new JarOutputStream(jar, manifest);
                addEntry(nested, "java/lang/VirtualThread.class", patchedVT);
                addEntry(nested, "java/lang/SimulatorThread.class", compiledST);
                addEntry(nested, "java/util/Timer.class", patchedTimer);
                addEntry(nested, "java/util/SimulatorTimer.class", compiledSimulatorTimer);
                nested.finish();
                jar.closeEntry();
                jar.setLevel(DEFAULT_COMPRESSION);
            } finally {
                deleteRecursively(tempDir);
            }
        }

        /** Reads a class byte-for-byte from the build JDK's runtime image ({@code jrt:/}). */
        private static byte[] readJdkClass(String internalName) throws IOException {
            var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            return readAllBytes(jrt.getPath("/modules/java.base/" + internalName + ".class"));
        }

        /** Reads {@code VirtualThread.class} from the JDK runtime image and strips {@code ACC_FINAL}. */
        private static byte[] patchVirtualThread() throws IOException {
            var cf = ClassFile.of();
            var model = cf.parse(readJdkClass("java/lang/VirtualThread"));
            int newFlags = model.flags().flagsMask() & ~ACC_FINAL;
            return cf.transformClass(model, (builder, element) -> {
                if (element instanceof AccessFlags) {
                    builder.withFlags(newFlags);
                } else {
                    builder.with(element);
                }
            });
        }

        /**
         * Reads {@code Timer.class} from the JDK runtime image and adds a no-start constructor
         * {@code Timer(String name, boolean isDaemon, int __noStartMarker)} whose body is a clone of
         * {@code Timer(String, boolean)} with the trailing {@code thread.start()} call replaced by
         * {@code pop} (preserving stack balance while omitting the side effect). {@code SimulatorTimer} uses
         * it via {@code super(name, isDaemon, 0)} to inherit Timer's initialization without starting the
         * parent's {@code TimerThread}.
         */
        private static byte[] patchTimer() throws IOException {
            var cf = ClassFile.of();
            var model = cf.parse(readJdkClass("java/util/Timer"));

            var sourceCtor = model.methods().stream()
                    .filter(m -> m.methodName().equalsString("<init>")
                            && m.methodTypeSymbol().equals(TIMER_CTOR))
                    .findFirst()
                    .orElseThrow(
                            () -> new IOException("java.util.Timer.<init>(String, boolean) not found in this JDK"));

            return cf.transformClass(
                    model,
                    ClassTransform.endHandler(classBuilder -> classBuilder.withMethod(
                            "<init>",
                            TIMER_NO_START_CTOR,
                            sourceCtor.flags().flagsMask(),
                            methodBuilder -> methodBuilder.withCode(codeBuilder -> {
                                for (var ce : sourceCtor.code().orElseThrow()) {
                                    if (isTimerThreadStart(ce)) {
                                        codeBuilder.pop();
                                    } else {
                                        codeBuilder.with(ce);
                                    }
                                }
                            }))));
        }

        /**
         * {@return whether {@code element} is the {@code thread.start()} call in Timer's constructor}.
         * javac compiles the call against the declared field type {@code TimerThread}, not the abstract
         * {@code Thread}, so both owners are accepted.
         */
        private static boolean isTimerThreadStart(CodeElement element) {
            return element instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKEVIRTUAL
                    && inv.method().name().equalsString("start")
                    && (inv.method().owner().asInternalName().equals("java/lang/Thread")
                            || inv.method().owner().asInternalName().equals("java/util/TimerThread"));
        }

        /**
         * Compiles a bundled patch source ({@code SimulatorThread.java} or {@code SimulatorTimer.java}) from
         * the classpath with {@code --patch-module java.base=<patchDir>} so it sees the patched JDK classes
         * already written into {@code patchDir}. {@return the compiled {@code .class} bytes}.
         */
        private static byte[] compilePatchSource(Path patchDir, String resource, String sourceRelPath)
                throws IOException {
            var classRelPath = sourceRelPath.substring(0, sourceRelPath.length() - ".java".length()) + ".class";
            String source;
            try (var sourceStream = OpendstPatchJar.class.getClassLoader().getResourceAsStream(resource)) {
                if (sourceStream == null) {
                    throw new IOException("Source resource not found on classpath: " + resource);
                }
                source = new String(sourceStream.readAllBytes(), UTF_8);
            }

            var sourceFile = patchDir.resolve("src", sourceRelPath);
            createDirectories(sourceFile.getParent());
            writeString(sourceFile, source);

            var outputDir = patchDir.resolve("classes");
            createDirectories(outputDir);

            var compiler = getSystemJavaCompiler();
            if (compiler == null) {
                throw new IOException("No system Java compiler available (javax.tools.JavaCompiler). "
                        + "Ensure the build runs on a JDK, not a JRE.");
            }

            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var fileManager = compiler.getStandardFileManager(diagnostics, null, UTF_8)) {
                var compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
                var options = of("--patch-module", "java.base=" + patchDir, "-d", outputDir.toString());

                var task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
                if (!task.call()) {
                    var errors = new StringBuilder("Failed to compile ")
                            .append(sourceRelPath)
                            .append(":\n");
                    for (var d : diagnostics.getDiagnostics()) {
                        errors.append("  ").append(d).append('\n');
                    }
                    throw new IOException(errors.toString());
                }
            }

            var classFile = outputDir.resolve(classRelPath);
            if (!exists(classFile)) {
                throw new IOException("Compiled class not found at: " + classFile);
            }
            return readAllBytes(classFile);
        }

        private static void addEntry(JarOutputStream jos, String name, byte[] content) throws IOException {
            jos.putNextEntry(new JarEntry(name));
            jos.write(content);
            jos.closeEntry();
        }

        /** Recursively deletes a directory tree. */
        @SuppressWarnings("ResultOfMethodCallIgnored")
        private static void deleteRecursively(Path dir) {
            try (var stream = walk(dir)) {
                stream.sorted(reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // Best-effort cleanup of temp directory
            }
        }
    }
}
