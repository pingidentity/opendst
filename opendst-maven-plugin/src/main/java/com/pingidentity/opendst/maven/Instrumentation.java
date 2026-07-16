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

import static com.pingidentity.opendst.common.AssertType.ALWAYS;
import static com.pingidentity.opendst.common.AssertType.SOMETIMES;
import static com.pingidentity.opendst.maven.Instrumentation.CallSiteTransform.callSiteTransformMethod;
import static com.pingidentity.opendst.maven.Instrumentation.CallSiteTransform.isDirectThreadSubclass;
import static com.pingidentity.opendst.maven.Instrumentation.CallSiteTransform.threadSubclassTransform;
import static java.lang.classfile.ClassHierarchyResolver.ofClassLoading;
import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.util.Map.entry;

import com.pingidentity.opendst.common.Assertion;
import com.pingidentity.opendst.maven.ClasspathResolver.Classpath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.Superclass;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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

/**
 * Orchestrates the offline instrumentation of application bytecode.
 *
 * <p>Transforms "Call-Sites" of non-deterministic JDK APIs (like {@code new Socket()})
 * to redirect them to the deterministic simulator. During transformation, each class is
 * also scanned for OpenDST assertions via {@link AssertionScanner}.
 */
final class Instrumentation {
    private final ExecutorService executor;

    /** Result of a class transformation task. */
    private record TransformationResult(String name, byte[] content) {}

    /** Thrown when an OpenDST assertion is found to be invalid (e.g. non-literal label). */
    @SuppressWarnings("serial")
    static final class AssertionValidationException extends RuntimeException {
        AssertionValidationException(String message) {
            super(message);
        }
    }

    Instrumentation(ExecutorService executor) {
        this.executor = executor;
    }

    /** Creates a new thread-safe set for collecting discovered assertions. */
    static Set<Assertion> newAssertionSet() {
        return ConcurrentHashMap.newKeySet();
    }

    /**
     * Rewrites an app's {@link Classpath}, emitting its instrumented content to {@code sink}: class
     * directories are merged into {@code WEB-INF/classes/<name>} entries (resources copied verbatim), and
     * each jar is rewritten in-memory into a single {@code WEB-INF/lib/<name>.jar} entry. {@return the
     * assertions discovered}. The caller's {@code sink} decides where those entries land; this only rewrites
     * bytecode — the shared {@code ClassHierarchyResolver} is built from the whole classpath, since a class
     * may reference a type in one of the jars.
     *
     * @throws AssertionValidationException if an assertion is invalid
     */
    Set<Assertion> instrument(Classpath sourceClasspath, EntrySink sink) throws IOException {
        var discovered = newAssertionSet();
        try (var projectLoader = sourceClasspath.newClassLoader(getClass().getClassLoader())) {
            var classFile = newClassFile(projectLoader);

            // Class directories merge into WEB-INF/classes/; instrumentEntries copies non-class resources verbatim.
            EntrySink classesSink = (name, content) -> sink.put("WEB-INF/classes/" + name, content);
            for (var classDir : sourceClasspath.classDirs()) {
                if (!exists(classDir)) {
                    continue;
                }
                try (var stream = walk(classDir)) {
                    instrumentEntries(
                            classFile, classesSink, discovered, classDir, stream.filter(Files::isRegularFile));
                }
            }

            // Each jar is rewritten in-memory and emitted as one WEB-INF/lib/<name>.jar entry.
            var libNames = new HashSet<String>();
            for (var jar : sourceClasspath.jars()) {
                var libEntry = "WEB-INF/lib/" + jar.getFileName();
                if (!libNames.add(libEntry)) {
                    throw new IOException(
                            "Two dependency jars map to the same entry '" + libEntry + "'; rename one to disambiguate");
                }
                sink.put(libEntry, instrumentedJar(classFile, jar, discovered));
            }
        }
        return discovered;
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
                        // Deliberate last resort: a type the app classpath can't resolve is assumed to extend
                        // Object. The classpath spans the whole app, so this is rare; a genuinely missing
                        // supertype would surface downstream as a VerifyError rather than here.
                        return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(OBJECT_DESC);
                    }
                })));
    }

    /** Where instrumented output goes — an entry in the opendst jar, a nested jar, a file in a directory. */
    @FunctionalInterface
    interface EntrySink {
        /** Writes one entry (a {@code /}-separated relative name) with the given bytes. */
        void put(String name, byte[] content) throws IOException;
    }

    /** Instruments a jar in-memory and {@return its bytes} — one {@code WEB-INF/lib/<name>.jar} entry. */
    private byte[] instrumentedJar(ClassFile classFile, Path jarPath, Set<Assertion> discovered) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null);
                var jos = new JarOutputStream(bytes)) {
            EntrySink sink = (name, content) -> {
                jos.putNextEntry(new JarEntry(name));
                jos.write(content);
                jos.closeEntry();
            };
            var root = fs.getPath("/");
            try (var stream = walk(root)) {
                instrumentEntries(classFile, sink, discovered, root, stream.filter(Files::isRegularFile));
            }
        }
        return bytes.toByteArray();
    }

    /** Core logic for instrumenting a stream of entries into an {@link EntrySink}. */
    private void instrumentEntries(
            ClassFile classFile, EntrySink sink, Set<Assertion> discovered, Path root, Stream<Path> entries)
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
                    AssertionScanner.discover(model, discovered);
                    var superclass = model.superclass().orElse(null);
                    var transform = superclass != null && isDirectThreadSubclass(superclass.asInternalName())
                            ? threadSubclassTransform().andThen(callSiteTransformMethod())
                            : callSiteTransformMethod();
                    return new TransformationResult(entryName, classFile.transformClass(model, transform));
                });
            } else if (!name.isEmpty()) {
                sink.put(name, readAllBytes(path));
            }
        }

        for (int i = 0; i < classTasks; i++) {
            try {
                var result = completionService.take().get();
                sink.put(result.name(), result.content());
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

    /**
     * Scans class bytecode for OpenDST {@link com.pingidentity.opendst.sdk.Assert} calls and builds the
     * static catalog of assertions.
     *
     * <p>OpenDST requires a complete, static catalog of all reachable assertions before any simulation
     * begins, so the runner can track coverage and generate heatmaps accurately. To keep the catalog
     * static, every {@link com.pingidentity.opendst.sdk.Assert} call's {@code message} argument must be a
     * <b>string literal</b> — enforced here.
     *
     * <p>Nested in {@link Instrumentation} because that is its sole consumer: each class is scanned as it is
     * transformed. It works by lightweight symbolic stack tracking per method, resolving the literal
     * argument at each Assert call site.
     */
    static final class AssertionScanner {

        private static final String ASSERT_OWNER = "com/pingidentity/opendst/sdk/Assert";

        private AssertionScanner() {}

        /**
         * Scans a class model for OpenDST assertions and adds them to the given set.
         *
         * @throws AssertionValidationException if an assertion uses a non-literal label
         */
        static void discover(ClassModel model, Set<Assertion> discovered) {
            try {
                var className = model.thisClass().asInternalName().replace('/', '.');
                for (var method : model.methods()) {
                    var methodName = method.methodName().toString();
                    for (var code : method.code().stream().toList()) {
                        scanMethod(code, className, methodName, discovered);
                    }
                }
            } catch (AssertionValidationException ave) {
                throw ave;
            } catch (Exception e) {
                // Best effort discovery for application code. Don't crash on complex third-party bytecode.
            }
        }

        private static void scanMethod(
                Iterable<? extends CodeElement> code, String className, String methodName, Set<Assertion> discovered) {
            // Symbolic stack tracking must be sequential per method to be correct.
            var stack = new ArrayList<String>();
            int currentLine = -1;
            for (var element : code) {
                switch (element) {
                    case LineNumber ln -> currentLine = ln.line();
                    case ConstantInstruction ci -> {
                        var val = ci.constantValue();
                        stack.add(val instanceof String s ? s : "");
                    }
                    case InvokeInstruction inv -> {
                        if (inv.method().owner().asInternalName().equals(ASSERT_OWNER)) {
                            handleAssertCall(inv, stack, className, methodName, currentLine, discovered);
                        }
                        updateStackForInvoke(inv, stack);
                    }
                    case LoadInstruction _ -> stack.add(null);
                    case FieldInstruction fi -> updateStackForField(fi, stack);
                    case OperatorInstruction oi -> {
                        int pop = getOperatorPopCount(oi.opcode());
                        for (int k = 0; k < pop && !stack.isEmpty(); k++) {
                            stack.removeLast();
                        }
                        stack.add(null);
                    }
                    case StackInstruction si -> handleStackInstruction(si, stack);
                    case Instruction instr -> {
                        var opcode = instr.opcode();
                        if (opcode == Opcode.NEW) {
                            stack.add(null);
                        } else if (opcode == Opcode.INSTANCEOF || opcode == Opcode.ARRAYLENGTH) {
                            // Pop one, push one (net zero but replace top with non-string)
                            if (!stack.isEmpty()) {
                                stack.removeLast();
                            }
                            stack.add(null);
                        } else if (opcode.name().contains("ALOAD")
                                || opcode.name().contains("ASTORE")) {
                            stack.clear(); // Conservatively clear on complex array operations
                        }
                    }
                    default -> {}
                }
            }
        }

        private static void handleAssertCall(
                InvokeInstruction inv,
                List<String> stack,
                String className,
                String methodName,
                int line,
                Set<Assertion> discovered) {
            var name = inv.method().name().toString();
            var type = inv.typeSymbol();
            var params = type.parameterList();
            int pos = 0;
            for (int i = params.size() - 1; i >= 0; i--) {
                pos += TypeKind.from(params.get(i)).slotSize();
                if (params.get(i).descriptorString().equals("Ljava/lang/String;")) {
                    break;
                }
            }

            String message = null;
            if (stack.size() >= pos) {
                message = stack.get(stack.size() - pos);
            }

            if (message == null || message.isEmpty()) {
                throw new AssertionValidationException(
                        "Invalid OpenDST assertion in %s.%s (line %d): message must be a string literal for Assert.%s%s"
                                .formatted(className, methodName, line, name, type.displayDescriptor()));
            }
            var kind = name.startsWith("sometimes") || name.equals("reachable") ? SOMETIMES : ALWAYS;
            discovered.add(new Assertion(kind, message, className, line));
        }

        private static void updateStackForInvoke(InvokeInstruction inv, List<String> stack) {
            int pop = getPopCount(inv.typeSymbol(), inv.opcode() != Opcode.INVOKESTATIC);
            for (int k = 0; k < pop && !stack.isEmpty(); k++) {
                stack.removeLast();
            }
            int push = TypeKind.from(inv.typeSymbol().returnType()).slotSize();
            for (int k = 0; k < push; k++) {
                stack.add(null);
            }
        }

        private static void updateStackForField(FieldInstruction fi, List<String> stack) {
            boolean isPut = fi.opcode() == Opcode.PUTFIELD || fi.opcode() == Opcode.PUTSTATIC;
            boolean isStatic = fi.opcode() == Opcode.GETSTATIC || fi.opcode() == Opcode.PUTSTATIC;
            int slots = TypeKind.from(fi.typeSymbol()).slotSize();
            if (isPut) {
                int pop = slots + (isStatic ? 0 : 1);
                for (int k = 0; k < pop && !stack.isEmpty(); k++) {
                    stack.removeLast();
                }
            } else {
                if (!isStatic && !stack.isEmpty()) {
                    stack.removeLast();
                }
                for (int k = 0; k < slots; k++) {
                    stack.add(null);
                }
            }
        }

        /** Calculates how many stack slots an invocation pops. */
        private static int getPopCount(MethodTypeDesc type, boolean hasReceiver) {
            int count = hasReceiver ? 1 : 0;
            for (var arg : type.parameterList()) {
                count += TypeKind.from(arg).slotSize();
            }
            return count;
        }

        /** Returns the number of stack slots popped by an operator instruction. */
        private static int getOperatorPopCount(Opcode opcode) {
            return switch (opcode) {
                case INEG,
                        LNEG,
                        FNEG,
                        DNEG,
                        I2L,
                        I2F,
                        I2D,
                        L2I,
                        L2F,
                        L2D,
                        F2I,
                        F2L,
                        F2D,
                        D2I,
                        D2L,
                        D2F,
                        I2B,
                        I2C,
                        I2S -> 1;
                default -> 2;
            };
        }

        /** Simulates stack manipulation instructions (DUP, SWAP, etc.) on the symbolic stack. */
        private static void handleStackInstruction(StackInstruction si, List<String> stack) {
            if (stack.isEmpty()) {
                return;
            }
            switch (si.opcode()) {
                case POP -> stack.removeLast();
                case POP2 -> {
                    stack.removeLast();
                    if (!stack.isEmpty()) {
                        stack.removeLast();
                    }
                }
                case DUP -> stack.add(stack.getLast());
                case DUP_X1 -> {
                    if (stack.size() < 2) {
                        return;
                    }
                    var top = stack.removeLast();
                    var next = stack.removeLast();
                    stack.add(top);
                    stack.add(next);
                    stack.add(top);
                }
                case DUP_X2 -> {
                    if (stack.size() < 3) {
                        return;
                    }
                    var top = stack.removeLast();
                    var n1 = stack.removeLast();
                    var n2 = stack.removeLast();
                    stack.add(top);
                    stack.add(n2);
                    stack.add(n1);
                    stack.add(top);
                }
                case DUP2 -> {
                    if (stack.size() < 2) {
                        return;
                    }
                    var v1 = stack.getLast();
                    var v2 = stack.get(stack.size() - 2);
                    stack.add(v2);
                    stack.add(v1);
                }
                case SWAP -> {
                    if (stack.size() < 2) {
                        return;
                    }
                    var v1 = stack.removeLast();
                    var v2 = stack.removeLast();
                    stack.add(v1);
                    stack.add(v2);
                }
                default -> {}
            }
        }
    }
}
