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

import static com.pingidentity.opendst.maven.BuildMojo.MainClassScanner.toHostname;
import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.sampledFrom;
import static java.util.Locale.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link BuildMojo.MainClassScanner}. */
public class MainClassScannerTest {

    /** Class-name fragments covering what {@code toHostname} branches on: acronym runs, digits, single letters. */
    private static final String[] WORDS = {
        "My", "Server", "Foo", "App", "Client", "DST", "HTTP", "Http2", "Node9", "A", "AB", "X"
    };

    @Test
    public void mapsKnownClassNamesToHostnames() {
        Map<String, String> cases = Map.of(
                "App", "app",
                "EasyApp", "easy-app",
                "MyServer", "my-server",
                "MyFooServer", "my-foo-server",
                "FlakyDST", "flaky-dst",
                "MyDSTServer", "my-dst-server",
                "DSTServer", "dst-server",
                "DST", "dst",
                "Http2Client", "http2-client",
                "My_Server", "my.server");
        cases.forEach((in, expected) -> assertEquals(expected, toHostname(in), in));
    }

    /**
     * Properties of {@link BuildMojo.MainClassScanner#toHostname} over generated Java-like class names — a
     * first {@link #WORDS word} followed by more words, each either concatenated ({@code MyServer}) or
     * underscore-separated ({@code My_Server}). hegel replaces the previous hand-rolled {@code Random}
     * generator (and shrinks failures to a minimal name).
     */
    @HegelTest
    void toHostnameIsWellFormedAndLossless(TestCase tc) {
        var sb = new StringBuilder(tc.draw(sampledFrom(WORDS), "word0"));
        int extra = tc.draw(sampledFrom(0, 1, 2, 3), "extraWords");
        for (int i = 0; i < extra; i++) {
            if (tc.draw(booleans(), "underscore" + i)) {
                sb.append('_');
            }
            sb.append(tc.draw(sampledFrom(WORDS), "word" + (i + 1)));
        }
        var name = sb.toString();
        var result = toHostname(name);

        // Lowercase only.
        assertEquals(result.toLowerCase(ROOT), result, name);
        // No leading or trailing separator.
        assertFalse(result.startsWith("-") || result.endsWith("-"), () -> name + " -> " + result);
        // Character-preserving: stripping separators and mapping '.' back to '_' recovers the lowercased input.
        assertEquals(name.toLowerCase(ROOT), result.replace("-", "").replace(".", "_"), name);
        // Well-formed: alphanumeric segments separated by single '-' or '.' — no empty or consecutive separators.
        assertTrue(result.matches("[a-z0-9]+([.\\-][a-z0-9]+)*"), () -> name + " -> " + result);
    }
}
