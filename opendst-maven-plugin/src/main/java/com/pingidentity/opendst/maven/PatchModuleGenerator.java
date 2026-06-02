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
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

import java.io.IOException;
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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

/**
 * Generates the {@code opendst-patch.jar} containing:
 * <ol>
 *   <li>{@code java/lang/VirtualThread.class} — with {@code ACC_FINAL} stripped</li>
 *   <li>{@code java/lang/SimulatorThread.class} — compiled from source, extends non-final
 *       {@code VirtualThread}, mirrors all {@code Thread} constructors</li>
 * </ol>
 *
 * <p>This JAR is used with {@code --patch-module java.base=opendst-patch.jar} to allow
 * Thread subclasses (rewritten to extend {@code SimulatorThread}) to run as virtual threads
 * under the deterministic simulator.
 *
 * <p><strong>JDK version constraint:</strong> The patched {@code VirtualThread.class} is read
 * byte-for-byte from the build JDK's runtime image ({@code jrt:/}), and {@code SimulatorThread}
 * is compiled against it. This means the generated JAR is tied to the exact JDK version used
 * during the Maven build. Running the simulation on a different JDK major version will fail at
 * startup with a version mismatch error. Running on a different update/patch of the same major
 * version will produce a warning. To fix either case, rebuild the project with the target JDK.
 *
 * <p>The generation process:
 * <ol>
 *   <li>Read {@code VirtualThread.class} from the JDK runtime image and strip {@code ACC_FINAL}</li>
 *   <li>Write the patched class to a temp directory</li>
 *   <li>Compile {@code SimulatorThread.java} (bundled as a classpath resource) with
 *       {@code --patch-module java.base=<tempDir>} so it sees the non-final VirtualThread</li>
 *   <li>Package both classes into {@code opendst-patch.jar} with a build JDK version manifest</li>
 * </ol>
 */
final class PatchModuleGenerator {

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
    private static final MethodTypeDesc TIMER_NO_START_CTOR = MethodTypeDesc.of(CD_void, CD_String, CD_boolean, CD_int);

    /**
     * Generates {@code opendst-patch.jar} at the given path.
     *
     * <p>The generated JAR contains a MANIFEST with a {@code Build-Jdk-Version} attribute
     * recording the exact JDK runtime version used during the build. This enables a runtime
     * check to ensure the simulation JDK matches the build JDK.
     *
     * @param outputJar the path where the JAR will be written
     * @throws IOException if an I/O error occurs
     */
    static void generate(Path outputJar) throws IOException {
        createDirectories(outputJar.getParent());

        var tempDir = createTempDirectory("opendst-patch");
        try {
            // 1. Patch VirtualThread: strip ACC_FINAL and write to temp dir
            var patchedVT = patchVirtualThread();
            createDirectories(tempDir.resolve("java/lang"));
            write(tempDir.resolve("java/lang/VirtualThread.class"), patchedVT);

            // 2. Patch Timer: add a no-start ctor and write to temp dir so SimulatorTimer compiles against it
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

            // 5. Package into opendst-patch.jar
            var manifest = new Manifest();
            manifest.getMainAttributes().put(MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes()
                    .putValue(BUILD_JDK_VERSION_ATTR, Runtime.version().toString());
            try (var jos = new JarOutputStream(newOutputStream(outputJar), manifest)) {
                addEntry(jos, "java/lang/VirtualThread.class", patchedVT);
                addEntry(jos, "java/lang/SimulatorThread.class", compiledST);
                addEntry(jos, "java/util/Timer.class", patchedTimer);
                addEntry(jos, "java/util/SimulatorTimer.class", compiledSimulatorTimer);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Reads a class byte-for-byte from the build JDK's runtime image ({@code jrt:/}). */
    private static byte[] readJdkClass(String internalName) throws IOException {
        var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        return readAllBytes(jrt.getPath("/modules/java.base/" + internalName + ".class"));
    }

    /**
     * Reads {@code VirtualThread.class} from the JDK runtime image and strips {@code ACC_FINAL}.
     */
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
     * {@code Timer(String name, boolean isDaemon, int __noStartMarker)} whose body is a clone
     * of {@code Timer(String, boolean)} with the trailing {@code thread.start()} call replaced
     * by {@code pop} (preserving stack balance while omitting the side effect).
     *
     * <p>{@link SimulatorTimer} uses this ctor via {@code super(name, isDaemon, 0)} to inherit
     * Timer's queue/thread/cleanup initialization without starting the parent's
     * {@code TimerThread}. The third {@code int} parameter is a sentinel that distinguishes the
     * new ctor from the public {@code Timer(String, boolean)} and carries no information.
     */
    private static byte[] patchTimer() throws IOException {
        var cf = ClassFile.of();
        var model = cf.parse(readJdkClass("java/util/Timer"));

        var sourceCtor = model.methods().stream()
                .filter(m -> m.methodName().equalsString("<init>")
                        && m.methodTypeSymbol().equals(TIMER_CTOR))
                .findFirst()
                .orElseThrow(() -> new IOException("java.util.Timer.<init>(String, boolean) not found in this JDK"));

        // Append the no-start ctor: a clone of Timer(String, boolean) with thread.start() dropped.
        return cf.transformClass(
                model,
                ClassTransform.endHandler(classBuilder -> classBuilder.withMethod(
                        "<init>",
                        TIMER_NO_START_CTOR,
                        sourceCtor.flags().flagsMask(),
                        methodBuilder -> methodBuilder.withCode(codeBuilder -> {
                            for (var ce : sourceCtor.code().orElseThrow()) {
                                if (isTimerThreadStart(ce)) {
                                    // Drop thread.start(): pop the receiver pushed by the preceding
                                    // getfield, keeping the stack balanced without the side effect.
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
     * Compiles a bundled patch source ({@code SimulatorThread.java} or {@code SimulatorTimer.java})
     * from the classpath: reads {@code resource}, writes it under {@code patchDir/src/<sourceRelPath>},
     * and compiles it with {@code --patch-module java.base=<patchDir>} so it sees the patched JDK
     * classes already written into {@code patchDir}.
     *
     * @param patchDir      directory holding the patched JDK classes the source compiles against
     * @param resource      classpath resource path of the {@code .java} source
     * @param sourceRelPath package-relative path of the source (e.g. {@code java/lang/SimulatorThread.java})
     * @return the compiled {@code .class} bytes
     */
    private static byte[] compilePatchSource(Path patchDir, String resource, String sourceRelPath) throws IOException {
        var classRelPath = sourceRelPath.substring(0, sourceRelPath.length() - ".java".length()) + ".class";
        // Read source from classpath resource
        String source;
        try (var sourceStream = PatchModuleGenerator.class.getClassLoader().getResourceAsStream(resource)) {
            if (sourceStream == null) {
                throw new IOException("Source resource not found on classpath: " + resource);
            }
            source = new String(sourceStream.readAllBytes(), UTF_8);
        }

        // Write source to temp dir for javac
        var sourceFile = patchDir.resolve("src", sourceRelPath);
        createDirectories(sourceFile.getParent());
        writeString(sourceFile, source);

        // Compile with --patch-module so javac sees the patched JDK classes
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

    private PatchModuleGenerator() {}
}
