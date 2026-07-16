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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compiles a single top-level Java class from a source {@code String} to its bytecode, in memory. Tests use
 * this to describe probe classes in readable Java rather than hand-building bytecode; referenced types must be
 * on the test classpath (the JDK and {@code opendst-sdk}). Kept for real, compilable constructs — cases that
 * synthesize call descriptors Java can't express (arbitrary arg shapes) still build bytecode directly.
 */
final class SourceCompiler {

    private SourceCompiler() {}

    /** {@return the bytecode of the class {@code binaryName} defined by {@code source}}. */
    static byte[] compile(String binaryName, String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var bytecode = new ByteArrayOutputStream();

        var sourceFile = new SimpleJavaFileObject(URI.create("string:///" + binaryName + ".java"), Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        try (JavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, UTF_8)) {
            var fileManager = new ForwardingJavaFileManager<>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(
                        Location location, String className, Kind kind, FileObject sibling) {
                    return new SimpleJavaFileObject(URI.create("bytes:///" + className), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return bytecode;
                        }
                    };
                }
            };
            var task = compiler.getTask(null, fileManager, diagnostics, null, null, List.of(sourceFile));
            if (!task.call()) {
                throw new IllegalStateException("Compilation failed:\n" + diagnostics.getDiagnostics());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytecode.toByteArray();
    }
}
