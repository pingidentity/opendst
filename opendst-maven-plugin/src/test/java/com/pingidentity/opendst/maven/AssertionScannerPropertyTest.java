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
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingidentity.opendst.common.AssertType;
import com.pingidentity.opendst.common.Assertion;
import com.pingidentity.opendst.maven.Instrumentation.AssertionScanner;
import com.pingidentity.opendst.maven.Instrumentation.AssertionValidationException;
import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Property-based tests for {@link AssertionScanner}. The scanner recovers each {@code Assert.*} call's
 * string-literal message by lightweight symbolic stack tracking, so the interesting input dimension is the
 * <em>shape</em> of the call site — the number and slot-width of the surrounding arguments and how they are
 * pushed — not the literal's text. Cases synthesize a single {@code Assert} call via the ClassFile API (the
 * scanner reads the call descriptor, not the target, so no real {@code Assert} class is needed) and vary that
 * shape with hegel, asserting the extraction is invariant to it.
 */
class AssertionScannerPropertyTest {

    private static final ClassDesc PROBE = ClassDesc.of("com.pingidentity.opendst.gen.Probe");
    private static final ClassDesc ASSERT = ClassDesc.of("com.pingidentity.opendst.sdk.Assert");
    private static final MethodTypeDesc COND_MSG = MethodTypeDesc.of(CD_void, CD_boolean, CD_String);

    /** Expected kind per {@code Assert} method name, by intent (not by mirroring the scanner's own rule). */
    private static final Map<String, AssertType> KIND_BY_NAME = Map.of(
            "always", ALWAYS,
            "alwaysOrUnreachable", ALWAYS,
            "unreachable", ALWAYS,
            "alwaysGreaterThan", ALWAYS,
            "alwaysSome", ALWAYS,
            "sometimes", SOMETIMES,
            "reachable", SOMETIMES,
            "sometimesLessThan", SOMETIMES,
            "sometimesAll", SOMETIMES);

    /** A non-message argument, by JVM type — the interesting axis is slot width (int/ref = 1, long/double = 2). */
    private enum Arg {
        INT(CD_int, CodeBuilder::iconst_0),
        BOOL(CD_boolean, CodeBuilder::iconst_1),
        REF(CD_Object, CodeBuilder::aconst_null),
        LONG(CD_long, CodeBuilder::lconst_0),
        DOUBLE(CD_double, CodeBuilder::dconst_0);

        final ClassDesc desc;
        final Consumer<CodeBuilder> push;

        Arg(ClassDesc desc, Consumer<CodeBuilder> push) {
            this.desc = desc;
            this.push = push;
        }
    }

    /**
     * Invariant — "some things never change": the literal message is extracted whatever the surrounding call
     * shape. Leading args of any type (including wide {@code long}/{@code double}) and any number of trailing
     * reference args (the {@code Map details} overloads) must not shift where the scanner locates the message.
     */
    @HegelTest
    void extractsMessageWhateverTheCallShape(TestCase tc) {
        var leading = tc.draw(lists(sampledFrom(Arg.values())), "leading");
        var trailing = tc.draw(lists(sampledFrom(Arg.REF)), "trailing"); // details maps are references
        var message = "m" + tc.draw(text(), "message");

        var found = scan(assertCall("always", leading, trailing, message));

        assertEquals(Set.of(message), messages(found));
        assertEquals(ALWAYS, found.iterator().next().kind());
    }

    /**
     * Different paths, same destination: pushing the same args after a run of stack-neutral {@code iconst;
     * pop} pairs yields the same discovered message — the symbolic stack must not drift on balanced ops.
     */
    @HegelTest
    void extractionIsInvariantToStackNeutralPrefix(TestCase tc) {
        var noops = tc.draw(lists(sampledFrom(Arg.INT)), "noops");
        var message = "m" + tc.draw(text(), "message");

        var found = scan(ClassFile.of()
                .build(
                        PROBE,
                        cb -> cb.withMethodBody("probe", MethodTypeDesc.of(CD_void), ACC_PUBLIC | ACC_STATIC, code -> {
                            for (int i = 0; i < noops.size(); i++) {
                                code.iconst_0();
                                code.pop();
                            }
                            code.iconst_1();
                            code.loadConstant(message);
                            code.invokestatic(ASSERT, "always", COND_MSG);
                            code.return_();
                        })));

        assertEquals(Set.of(message), messages(found));
    }

    /**
     * Validation invariant: a message that is not a compile-time constant is rejected, however it is produced,
     * with a diagnostic naming the offending {@code Assert} method. Replaces the former {@code it-plugin-error}
     * invoker IT, which forked a Maven build only to grep the same message out of the failure log.
     */
    @Test
    void rejectsNonLiteralMessageWithHelpfulError() {
        var computedReachable = ClassFile.of().parse(SourceCompiler.compile("Probe", """
                        import com.pingidentity.opendst.sdk.Assert;
                        class Probe { static void body(int level) { Assert.reachable("level-" + level); } }
                        """));
        var error = assertThrows(
                AssertionValidationException.class,
                () -> AssertionScanner.discover(computedReachable, new HashSet<>()));
        assertTrue(error.getMessage().contains("message must be a string literal"), error.getMessage());
        assertTrue(error.getMessage().contains("Assert.reachable"), error.getMessage());

        var fromParameter = ClassFile.of().parse(SourceCompiler.compile("Probe", """
                        import com.pingidentity.opendst.sdk.Assert;
                        class Probe { static void body(String msg) { Assert.always(true, msg); } }
                        """));
        assertThrows(
                AssertionValidationException.class, () -> AssertionScanner.discover(fromParameter, new HashSet<>()));
    }

    /** Validation classifies each {@code Assert.*} by name — {@code sometimes*}/{@code reachable} are SOMETIMES. */
    @HegelTest
    void classifiesKindByMethodName(TestCase tc) {
        var name = tc.draw(sampledFrom(List.copyOf(KIND_BY_NAME.keySet())), "name");
        var found = scan(assertCall(name, List.of(Arg.BOOL), List.of(), "m"));
        assertEquals(KIND_BY_NAME.get(name), found.iterator().next().kind());
    }

    @Test
    void discoversEveryAssertCallInAMethod() {
        var bytes = SourceCompiler.compile("Probe", """
                import com.pingidentity.opendst.sdk.Assert;
                class Probe {
                    static void body() {
                        Assert.always(true, "first");
                        Assert.sometimes(false, "second");
                    }
                }
                """);
        assertEquals(Set.of("first", "second"), messages(scan(bytes)));
    }

    /** Builds {@code Assert.<method>(<leading...>, "message", <trailing...>)} in a static probe method. */
    private static byte[] assertCall(String method, List<Arg> leading, List<Arg> trailing, String message) {
        var params = new ArrayList<ClassDesc>();
        leading.forEach(a -> params.add(a.desc));
        params.add(CD_String);
        trailing.forEach(a -> params.add(a.desc));
        var descriptor = MethodTypeDesc.of(CD_void, params.toArray(ClassDesc[]::new));
        return ClassFile.of()
                .build(
                        PROBE,
                        cb -> cb.withMethodBody("probe", MethodTypeDesc.of(CD_void), ACC_PUBLIC | ACC_STATIC, code -> {
                            leading.forEach(a -> a.push.accept(code));
                            code.loadConstant(message);
                            trailing.forEach(a -> a.push.accept(code));
                            code.invokestatic(ASSERT, method, descriptor);
                            code.return_();
                        }));
    }

    private static Set<Assertion> scan(byte[] classBytes) {
        var found = new HashSet<Assertion>();
        AssertionScanner.discover(ClassFile.of().parse(classBytes), found);
        return found;
    }

    private static Set<String> messages(Set<Assertion> found) {
        return found.stream().map(Assertion::message).collect(toSet());
    }
}
