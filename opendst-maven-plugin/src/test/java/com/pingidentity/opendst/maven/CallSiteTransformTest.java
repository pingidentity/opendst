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
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.lang.classfile.Opcode.NEW;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Property-based tests for {@link Instrumentation.CallSiteTransform}, the build-time rewrite that redirects
 * non-deterministic JDK call sites to their simulator equivalents. Cases synthesize a method as a sequence of
 * self-contained, stack-neutral snippets (each a {@code new X();} or {@code Foo.bar();}) via the ClassFile
 * API, drawn by hegel, then assert invariants over the rewritten bytecode.
 */
class CallSiteTransformTest {

    private static final ClassDesc PROBE = ClassDesc.of("com.pingidentity.opendst.gen.Probe");
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(CD_void);

    private static final String THREAD = "java/lang/Thread";
    private static final String TIMER = "java/util/Timer";
    private static final String SIGNALS = "com/pingidentity/opendst/sdk/Signals";
    private static final String ASSERT = "com/pingidentity/opendst/sdk/Assert";
    private static final String SIM_THREAD = "java/lang/SimulatorThread";
    private static final String SIM_TIMER = "java/util/SimulatorTimer";
    private static final String SIGNALS_IMPL = "com/pingidentity/opendst/sdk/SignalsImpl";
    private static final String ASSERT_IMPL = "com/pingidentity/opendst/sdk/AssertImpl";
    private static final String OBJECT = "java/lang/Object";
    private static final String OTHER = "com/example/Other";

    /** A stack-neutral call-site snippet, plus the owner it should redirect to (or stay at, for non-targets). */
    private enum Snippet {
        NEW_THREAD(code -> newDiscard(code, THREAD), NEW, SIM_THREAD),
        NEW_TIMER(code -> newDiscard(code, TIMER), NEW, SIM_TIMER),
        NEW_OBJECT(code -> newDiscard(code, OBJECT), NEW, OBJECT),
        CALL_SIGNALS(code -> code.invokestatic(desc(SIGNALS), "sig", VOID), INVOKESTATIC, SIGNALS_IMPL),
        CALL_ASSERT(code -> code.invokestatic(desc(ASSERT), "chk", VOID), INVOKESTATIC, ASSERT_IMPL),
        CALL_OTHER(code -> code.invokestatic(desc(OTHER), "foo", VOID), INVOKESTATIC, OTHER);

        final Consumer<CodeBuilder> emit;
        final Opcode opcode;
        final String expectedOwner;

        Snippet(Consumer<CodeBuilder> emit, Opcode opcode, String expectedOwner) {
            this.emit = emit;
            this.opcode = opcode;
            this.expectedOwner = expectedOwner;
        }
    }

    /**
     * Completeness + preservation invariant: every redirectable call site is rewritten to its simulator owner
     * (and none of the original owners survives), while every non-target call site is left untouched — one
     * occurrence out, one occurrence in, for whatever mix and multiplicity was generated.
     */
    @HegelTest
    void redirectsTargetsAndPreservesTheRest(TestCase tc) {
        var snippets = tc.draw(lists(sampledFrom(Snippet.values())), "snippets");
        var sites = sites(instrument(probe(snippets)));

        // No original non-deterministic owner survives, as a NEW or as an invoked owner.
        for (var gone : List.of(THREAD, TIMER, SIGNALS, ASSERT)) {
            assertFalse(sites.stream().anyMatch(s -> s.owner().equals(gone)), "residual " + gone);
        }
        // Each snippet contributes exactly one call site at its expected (possibly redirected) owner.
        for (var kind : Snippet.values()) {
            long expected = snippets.stream().filter(s -> s == kind).count();
            long actual = sites.stream()
                    .filter(s -> s.opcode() == kind.opcode && s.owner().equals(kind.expectedOwner))
                    .count();
            assertEquals(expected, actual, kind + " → " + kind.expectedOwner);
        }
    }

    /** Idempotence: re-running the transform over already-instrumented bytecode changes no call site. */
    @HegelTest
    void transformIsIdempotent(TestCase tc) {
        var snippets = tc.draw(lists(sampledFrom(Snippet.values())), "snippets");
        var once = instrument(probe(snippets));
        assertEquals(sites(once), sites(instrument(once)));
    }

    /** Only a <em>direct</em> {@code Thread} subclass has its superclass rewritten; others are left alone. */
    @HegelTest
    void rewritesOnlyDirectThreadSuperclass(TestCase tc) {
        var superName = tc.draw(sampledFrom("java.lang.Thread", "java.lang.Object", "com.example.Base"), "super");
        var bytes = ClassFile.of().build(PROBE, cb -> {
            cb.withSuperclass(ClassDesc.of(superName));
            cb.withMethodBody("probe", VOID, ACC_PUBLIC | ACC_STATIC, CodeBuilder::return_);
        });
        var out = ClassFile.of().parse(instrument(bytes));
        var expected = "java.lang.Thread".equals(superName) ? SIM_THREAD : superName.replace('.', '/');
        assertEquals(expected, out.superclass().orElseThrow().asInternalName());
    }

    /**
     * A readable, source-level example (the properties above fuzz the bytecode; compiling per generated case
     * would be ~100x slower, so this single case is compiled from Java instead). Every redirectable owner is
     * gone and its simulator target present; the untouched owners remain.
     */
    @Test
    void redirectsRealCallSites() {
        var bytes = SourceCompiler.compile("Probe", """
                import com.pingidentity.opendst.sdk.Assert;
                import com.pingidentity.opendst.sdk.Signals;
                class Probe {
                    static void body() {
                        new Thread();
                        new java.util.Timer();
                        new Object();
                        Signals.ready();
                        Assert.reachable("x");
                        System.gc();
                    }
                }
                """);

        var owners = sites(instrument(bytes)).stream().map(Site::owner).collect(toSet());

        assertTrue(
                owners.containsAll(
                        Set.of(SIM_THREAD, SIM_TIMER, SIGNALS_IMPL, ASSERT_IMPL, OBJECT, "java/lang/System")),
                owners::toString);
        assertFalse(owners.contains(THREAD)
                || owners.contains(TIMER)
                || owners.contains(SIGNALS)
                || owners.contains(ASSERT));
    }

    // ------------------------------------------------------------------

    /** Runs the same transform pipeline {@link Instrumentation} uses, keyed on whether the super is Thread. */
    private static byte[] instrument(byte[] classBytes) {
        var cf = ClassFile.of();
        var model = cf.parse(classBytes);
        var directThreadSubclass = model.superclass()
                .map(s -> isDirectThreadSubclass(s.asInternalName()))
                .orElse(false);
        var transform = directThreadSubclass
                ? threadSubclassTransform().andThen(callSiteTransformMethod())
                : callSiteTransformMethod();
        return cf.transformClass(model, transform);
    }

    private static byte[] probe(List<Snippet> snippets) {
        return ClassFile.of()
                .build(
                        PROBE,
                        cb -> cb.withMethodBody("probe", VOID, ACC_PUBLIC | ACC_STATIC, code -> {
                            snippets.forEach(s -> s.emit.accept(code));
                            code.return_();
                        }));
    }

    private static void newDiscard(CodeBuilder code, String internalName) {
        var type = desc(internalName);
        code.new_(type);
        code.dup();
        code.invokespecial(type, "<init>", VOID);
        code.pop();
    }

    private static ClassDesc desc(String internalName) {
        return ClassDesc.ofInternalName(internalName);
    }

    /** A call site in the rewritten bytecode: the opcode and the owner it targets. */
    private record Site(Opcode opcode, String owner) {}

    private static List<Site> sites(byte[] classBytes) {
        var out = new ArrayList<Site>();
        for (var method : ClassFile.of().parse(classBytes).methods()) {
            method.code()
                    .ifPresent(code -> code.forEach(e -> {
                        if (e instanceof NewObjectInstruction n) {
                            out.add(new Site(NEW, n.className().asInternalName()));
                        } else if (e instanceof InvokeInstruction inv) {
                            out.add(new Site(inv.opcode(), inv.method().owner().asInternalName()));
                        }
                    }));
        }
        return out;
    }
}
