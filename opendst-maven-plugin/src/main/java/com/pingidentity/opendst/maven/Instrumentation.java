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

import static com.pingidentity.opendst.maven.Instrumentation.CallSiteTransform.callSiteTransformMethod;
import static com.pingidentity.opendst.maven.Instrumentation.CallSiteTransform.isDirectThreadSubclass;
import static com.pingidentity.opendst.maven.Instrumentation.CallSiteTransform.threadSubclassTransform;
import static java.lang.classfile.ClassHierarchyResolver.ofClassLoading;
import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.nio.file.Files.walkFileTree;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Map.entry;

import com.pingidentity.opendst.common.Assertion;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Superclass;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.apache.maven.plugin.logging.Log;

/**
 * Orchestrates the offline instrumentation of application bytecode.
 *
 * <p>Transforms "Call-Sites" of non-deterministic JDK APIs (like {@code new Socket()})
 * to redirect them to the deterministic simulator. During transformation, each class is
 * also scanned for OpenDST assertions via {@link PropertyDiscoverer}.
 */
final class Instrumentation {
    private final Path basePath;
    private final Path instrumentedAppsDir;
    private final ExecutorService executor;
    private final Log log;

    /** Result of a class transformation task. */
    private record TransformationResult(String name, byte[] content) {}

    /** Thrown when an OpenDST assertion is found to be invalid (e.g. non-literal label). */
    @SuppressWarnings("serial")
    static final class AssertionValidationException extends RuntimeException {
        AssertionValidationException(String message) {
            super(message);
        }
    }

    Instrumentation(Path basePath, Path instrumentedAppsDir, ExecutorService executor, Log log) {
        this.basePath = basePath;
        this.instrumentedAppsDir = instrumentedAppsDir;
        this.executor = executor;
        this.log = log;
    }

    /** Creates a new thread-safe set for collecting discovered assertions. */
    static Set<Assertion> newAssertionSet() {
        return ConcurrentHashMap.newKeySet();
    }

    /**
     * Instruments an exploded application directory (e.g., an unpacked WAR or artifact)
     * and writes the instrumented output under {@code instrumentedAppsDir/<appName>/}.
     *
     * <p>The source directory is expected to contain a {@code WEB-INF/} layout with
     * {@code classes/}, {@code lib/}, and optionally {@code fs/}.
     *
     * @param appName   the directory name under {@code apps/} in the output JAR
     * @param sourceDir the exploded application directory to instrument
     * @return a set of all OpenDST assertions discovered during instrumentation
     * @throws IOException if instrumentation fails due to I/O errors
     * @throws AssertionValidationException if an assertion is invalid
     */
    Set<Assertion> instrumentAppDir(String appName, Path sourceDir) throws IOException {
        log.info("Instrumenting app directory '%s' from %s".formatted(appName, sourceDir));
        var discovered = newAssertionSet();
        createDirectories(instrumentedAppsDir);
        var urls = collectClasspath(sourceDir, List.of());
        try (var projectLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
            var classFile = newClassFile(projectLoader);
            var outputDir = instrumentedAppsDir.resolve(appName);
            instrumentApplicationDirectory(classFile, sourceDir, outputDir, discovered);
        }
        return discovered;
    }

    /**
     * Instruments project classes and runtime dependency JARs, placing the output under
     * {@code instrumentedAppsDir/<appName>/WEB-INF/}.
     *
     * <p>Used when the project does <em>not</em> use {@code maven-war-plugin}. The source
     * directories are read but never modified; instrumented output is written entirely to
     * {@code instrumentedAppsDir}.
     *
     * @param appName        the application name (typically the Maven artifactId), used as
     *                       the directory name under {@code apps/} in the output JAR
     * @param classesDirs    the directories containing class files to instrument (e.g.,
     *                       {@code [target/classes]} for compile scope, or
     *                       {@code [target/classes, target/test-classes]} for test scope);
     *                       all directories are combined into a single {@code classes.jar}
     * @param dependencyJars the project's dependency JARs to instrument and copy
     * @return a set of all OpenDST assertions discovered during instrumentation
     * @throws IOException if instrumentation fails due to I/O errors
     * @throws AssertionValidationException if an assertion is invalid
     */
    Set<Assertion> instrumentClasses(String appName, List<Path> classesDirs, List<Path> dependencyJars)
            throws IOException {
        log.info("Instrumenting classes for %s".formatted(appName));
        var discovered = newAssertionSet();
        createDirectories(instrumentedAppsDir);
        var urls = collectClasspath(null, dependencyJars);
        try (var projectLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
            var classFile = newClassFile(projectLoader);

            // Instrument class directories → <appName>/WEB-INF/classes.jar
            var webInfDir = instrumentedAppsDir.resolve(appName).resolve("WEB-INF");
            instrumentClassesFolders(classFile, classesDirs, webInfDir.resolve("classes.jar"), discovered);

            // Instrument runtime dependency JARs → <appName>/WEB-INF/lib/
            var libDir = webInfDir.resolve("lib");
            for (var jarPath : dependencyJars) {
                var targetJar = libDir.resolve(jarPath.getFileName().toString());
                createDirectories(libDir);
                instrumentJar(classFile, jarPath, targetJar, discovered);
            }
        }
        return discovered;
    }

    /**
     * Instruments an exploded application directory, walking its file tree to find
     * {@code classes/} folders and JAR files.
     */
    private void instrumentApplicationDirectory(
            ClassFile classFile, Path sourceDir, Path outputDir, Set<Assertion> discovered) throws IOException {
        if (!exists(sourceDir)) {
            return;
        }

        walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals("classes") && !dir.equals(sourceDir)) {
                    var targetJar = outputDir.resolve(sourceDir.relativize(dir) + ".jar");
                    instrumentClassesFolder(classFile, dir, targetJar, discovered);
                    return SKIP_SUBTREE;
                }
                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var targetFile = outputDir.resolve(sourceDir.relativize(file));
                createDirectories(targetFile.getParent());

                if (file.getFileName().toString().endsWith(".jar")) {
                    instrumentJar(classFile, file, targetFile, discovered);
                } else {
                    copy(file, targetFile, REPLACE_EXISTING);
                }
                return CONTINUE;
            }
        });
    }

    private static final ClassDesc VIRTUAL_THREAD_DESC = ClassDesc.ofDescriptor("Ljava/lang/VirtualThread;");
    private static final ClassDesc TIMER_DESC = ClassDesc.ofDescriptor("Ljava/util/Timer;");
    private static final ClassDesc OBJECT_DESC = ClassDesc.ofDescriptor("Ljava/lang/Object;");

    /**
     * Creates a {@link ClassFile} instance configured with the given classloader for hierarchy resolution.
     *
     * <p>The fallback resolver handles classes injected via {@code --patch-module} at runtime but not
     * visible to the build-time classloader: {@code SimulatorThread} (extends {@code VirtualThread}) and
     * {@code SimulatorTimer} (extends {@code Timer}). Without these mappings, stack map frame
     * recomputation would resolve them as {@code Object}, causing {@link VerifyError} at runtime for
     * any rewritten call site (e.g. {@code Timer t = new SimulatorTimer(...)}).
     */
    private static ClassFile newClassFile(URLClassLoader loader) {
        return ClassFile.of(
                ClassHierarchyResolverOption.of(ofClassLoading(loader).orElse(desc -> {
                    if ("Ljava/lang/SimulatorThread;".equals(desc.descriptorString())) {
                        return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(VIRTUAL_THREAD_DESC);
                    } else if ("Ljava/util/SimulatorTimer;".equals(desc.descriptorString())) {
                        return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(TIMER_DESC);
                    } else {
                        return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(OBJECT_DESC);
                    }
                })));
    }

    /**
     * Builds a classpath for the {@link URLClassLoader} used during transformation.
     *
     * @param appDir    an exploded application directory, or {@code null} for non-WAR projects
     * @param extraJars additional JARs to include (e.g. runtime dependency JARs for JAR projects)
     */
    private URL[] collectClasspath(Path appDir, List<Path> extraJars) throws IOException {
        var urls = new ArrayList<URL>();
        var mainClassesDir = basePath.resolve("target/classes");
        if (exists(mainClassesDir)) {
            urls.add(mainClassesDir.toUri().toURL());
        }
        var testClassesDir = basePath.resolve("target/test-classes");
        if (exists(testClassesDir)) {
            urls.add(testClassesDir.toUri().toURL());
        }
        if (appDir != null && exists(appDir)) {
            try (var stream = walk(appDir)) {
                var paths = stream.filter(path -> (isDirectory(path)
                                        && path.getFileName().toString().equals("classes"))
                                || path.getFileName().toString().endsWith(".jar"))
                        .toList();
                for (var path : paths) {
                    urls.add(path.toUri().toURL());
                }
            }
        }
        for (var jar : extraJars) {
            urls.add(jar.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    /** Instruments a directory of class files and bundles them into a JAR. */
    private void instrumentClassesFolder(ClassFile classFile, Path sourceDir, Path targetJar, Set<Assertion> discovered)
            throws IOException {
        instrumentClassesFolders(classFile, List.of(sourceDir), targetJar, discovered);
    }

    /**
     * Instruments one or more directories of class files and bundles them into a single JAR.
     *
     * <p>Directories that do not exist are silently skipped. If no directories contain any
     * class files, the target JAR will be empty.
     */
    private void instrumentClassesFolders(
            ClassFile classFile, List<Path> sourceDirs, Path targetJar, Set<Assertion> discovered) throws IOException {
        createDirectories(targetJar.getParent());
        try (var jos = new JarOutputStream(newOutputStream(targetJar))) {
            for (var sourceDir : sourceDirs) {
                if (!exists(sourceDir)) {
                    continue;
                }
                try (var stream = walk(sourceDir)) {
                    instrumentEntries(classFile, jos, discovered, sourceDir, stream.filter(Files::isRegularFile));
                }
            }
        }
    }

    /** Instruments a JAR file and writes the result to a target location. */
    private void instrumentJar(ClassFile classFile, Path jarPath, Path targetJar, Set<Assertion> discovered)
            throws IOException {
        try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null);
                var jos = new JarOutputStream(newOutputStream(targetJar))) {
            var root = fs.getPath("/");
            try (var stream = walk(root)) {
                instrumentEntries(classFile, jos, discovered, root, stream.filter(Files::isRegularFile));
            }
        }
    }

    /** Core logic for instrumenting a stream of entries. */
    private void instrumentEntries(
            ClassFile classFile, JarOutputStream jos, Set<Assertion> discovered, Path root, Stream<Path> entries)
            throws IOException {
        var completionService = new ExecutorCompletionService<TransformationResult>(executor);
        int classTasks = 0;

        for (var it = entries.iterator(); it.hasNext(); ) {
            var path = it.next();
            // Normalize the entry name:
            // 1. Relativize against the root to get the path within the archive/folder.
            // 2. Remove leading slash (some FileSystems like ZipFileSystem return them).
            // 3. Force forward slashes for ZIP/JAR compatibility (especially on Windows).
            var name = root.relativize(path).toString();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            name = name.replace('\\', '/');
            if (name.endsWith(".class")) {
                classTasks++;
                final var entryName = name;
                completionService.submit(() -> {
                    var model = classFile.parse(readAllBytes(path));
                    PropertyDiscoverer.discover(model, discovered);
                    var superclass = model.superclass().orElse(null);
                    var transform = superclass != null && isDirectThreadSubclass(superclass.asInternalName())
                            ? threadSubclassTransform().andThen(callSiteTransformMethod())
                            : callSiteTransformMethod();
                    return new TransformationResult(entryName, classFile.transformClass(model, transform));
                });
            } else if (!name.isEmpty()) {
                var newEntry = new JarEntry(name);
                jos.putNextEntry(newEntry);
                jos.write(readAllBytes(path));
                jos.closeEntry();
            }
        }

        for (int i = 0; i < classTasks; i++) {
            try {
                var result = completionService.take().get();
                jos.putNextEntry(new JarEntry(result.name()));
                jos.write(result.content());
                jos.closeEntry();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while transforming entries in %s".formatted(root), e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AssertionValidationException ave) {
                    throw ave;
                }
                throw new IOException("Failed to transform class in %s".formatted(root), e);
            }
        }
    }

    /**
     * Build-time bytecode rewrites that redirect non-deterministic JDK APIs to their
     * deterministic simulator equivalents.
     *
     * <p>Nested in {@link Instrumentation} because that is its sole consumer; the agent
     * applies the same redirections at runtime via separate ByteBuddy advice.
     */
    static final class CallSiteTransform {
        private static final ClassDesc SIMULATOR_THREAD_CLASS = ClassDesc.of("java.lang.SimulatorThread");
        private static final ClassDesc SIMULATOR_TIMER_CLASS = ClassDesc.of("java.util.SimulatorTimer");
        private static final ClassDesc SIGNALS_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.sdk.SignalsImpl");
        private static final ClassDesc ASSERT_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.sdk.AssertImpl");

        private static final String THREAD_INTERNAL = "java/lang/Thread";
        private static final String TIMER_INTERNAL = "java/util/Timer";

        /** Maps internal class names of static method sources to their deterministic redirect targets. */
        static final Map<String, ClassDesc> REDIRECT_STATIC_METHODS = Map.ofEntries(
                entry("com/pingidentity/opendst/sdk/Signals", SIGNALS_IMPL_CLASS),
                entry("com/pingidentity/opendst/sdk/Assert", ASSERT_IMPL_CLASS));

        /** Returns a {@link ClassTransform} that rewrites call sites for deterministic simulation. */
        static ClassTransform callSiteTransformMethod() {
            return transformingMethods((methodBuilder, methodElement) -> {
                if (methodElement instanceof CodeModel codeModel) {
                    methodBuilder.transformCode(codeModel, new CallSiteTransformer());
                } else {
                    methodBuilder.with(methodElement);
                }
            });
        }

        /**
         * Returns a {@link ClassTransform} that rewrites direct {@code Thread} subclasses to
         * extend {@code SimulatorThread} instead, so they run as virtual threads under simulation.
         *
         * <p>This transform changes the superclass from {@code java/lang/Thread} to
         * {@code java/lang/SimulatorThread}. Constructor {@code super()} calls are rewritten by
         * {@link CallSiteTransformer} which handles all {@code INVOKESPECIAL Thread.<init>} rewrites.
         *
         * <p>Only <em>direct</em> {@code Thread} subclasses are rewritten. Transitive subclasses
         * (e.g. {@code MyThread extends ZooKeeperThread extends Thread}) are handled automatically
         * because their parent ({@code ZooKeeperThread}) has already been rewritten.
         */
        static ClassTransform threadSubclassTransform() {
            return (classBuilder, classElement) -> {
                if (classElement instanceof Superclass sc
                        && THREAD_INTERNAL.equals(sc.superclassEntry().asInternalName())) {
                    classBuilder.withSuperclass(SIMULATOR_THREAD_CLASS);
                } else {
                    classBuilder.with(classElement);
                }
            };
        }

        /**
         * Returns {@code true} if the given superclass internal name is {@code java/lang/Thread},
         * meaning the class is a direct Thread subclass that should be rewritten.
         */
        static boolean isDirectThreadSubclass(String superclassInternalName) {
            return THREAD_INTERNAL.equals(superclassInternalName);
        }

        /**
         * Rewrites call sites for deterministic simulation:
         * <ul>
         *   <li>{@code NEW java/lang/Thread} → {@code NEW java/lang/SimulatorThread}</li>
         *   <li>{@code INVOKESPECIAL Thread.<init>} → {@code INVOKESPECIAL SimulatorThread.<init>}</li>
         *   <li>{@code NEW java/util/Timer} → {@code NEW java/util/SimulatorTimer}</li>
         *   <li>{@code INVOKESPECIAL Timer.<init>} → {@code INVOKESPECIAL SimulatorTimer.<init>}</li>
         *   <li>{@code INVOKESTATIC Signals/Assert.method()} → {@code INVOKESTATIC SignalsImpl/AssertImpl.method()}</li>
         * </ul>
         */
        private static final class CallSiteTransformer implements CodeTransform {
            @Override
            public void accept(CodeBuilder builder, CodeElement element) {
                switch (element) {
                    case NewObjectInstruction i
                    when THREAD_INTERNAL.equals(i.className().asInternalName()) -> builder.new_(SIMULATOR_THREAD_CLASS);
                    case NewObjectInstruction i
                    when TIMER_INTERNAL.equals(i.className().asInternalName()) -> builder.new_(SIMULATOR_TIMER_CLASS);
                    case InvokeInstruction i -> handleInvoke(builder, i);
                    default -> builder.with(element);
                }
            }

            private void handleInvoke(CodeBuilder builder, InvokeInstruction i) {
                var owner = i.method().owner().asInternalName();
                if (i.opcode() == INVOKESPECIAL
                        && THREAD_INTERNAL.equals(owner)
                        && i.method().name().equalsString("<init>")) {
                    // Rewrite Thread.<init> → SimulatorThread.<init> (same descriptor).
                    // Covers both: new Thread(runnable) and super() calls in Thread subclasses.
                    builder.invokespecial(SIMULATOR_THREAD_CLASS, "<init>", i.typeSymbol());
                } else if (i.opcode() == INVOKESPECIAL
                        && TIMER_INTERNAL.equals(owner)
                        && i.method().name().equalsString("<init>")) {
                    // Rewrite Timer.<init> → SimulatorTimer.<init> (same descriptor).
                    // Covers `new Timer(...)` and any future super() calls from Timer subclasses.
                    builder.invokespecial(SIMULATOR_TIMER_CLASS, "<init>", i.typeSymbol());
                } else if (i.opcode() == INVOKESTATIC && REDIRECT_STATIC_METHODS.containsKey(owner)) {
                    var targetClass = Objects.requireNonNull(REDIRECT_STATIC_METHODS.get(owner));
                    builder.invokestatic(targetClass, i.method().name().toString(), i.typeSymbol());
                } else {
                    builder.with(i);
                }
            }
        }

        private CallSiteTransform() {
            // Prevent instantiation
        }
    }
}
