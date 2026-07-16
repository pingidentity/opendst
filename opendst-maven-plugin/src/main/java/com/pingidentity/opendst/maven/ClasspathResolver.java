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

import static java.nio.file.FileSystems.newFileSystem;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.walk;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Resolves a service's declared source — a Maven artifact, a directory, or the current project (compile or
 * test) — into a {@link Classpath}: its {@code apps/} output name plus the class directories and jars to
 * instrument. This is the resolution half of the build, separate from instrumentation; every dependency on
 * Maven's resolution machinery ({@link RepositorySystem}, {@link MavenProject}, aether) is confined here.
 */
final class ClasspathResolver {

    private final Path basePath;
    private final MavenProject project;
    private final MavenSession session;
    private final RepositorySystem repositorySystem;
    private final Log log;

    ClasspathResolver(
            Path basePath, MavenProject project, MavenSession session, RepositorySystem repositorySystem, Log log) {
        this.basePath = basePath;
        this.project = project;
        this.session = session;
        this.repositorySystem = repositorySystem;
        this.log = log;
    }

    /**
     * Resolves a Maven artifact from its GAV coordinate — fetched from the repositories, unpacked, and
     * exploded. {@return its {@link Classpath}}.
     */
    Classpath resolveArtifact(String gav) throws IOException, ArtifactResolutionException {
        var outputDir = artifactOutputDir(gav);
        return explodedClasspath(outputDir, unpackArtifact(gav, outputDir));
    }

    /**
     * Resolves an already-exploded WAR/artifact directory {@code dir} under the project basedir.
     * {@return its {@link Classpath}}.
     *
     * @throws IOException if {@code dir} does not exist
     */
    Classpath resolveDirectory(String dir) throws IOException {
        var dirPath = basePath.resolve(dir);
        if (!exists(dirPath)) {
            throw new IOException("Non-existent directory: " + dirPath);
        }
        var name = Path.of(dir).getFileName();
        if (name == null) {
            throw new IOException("Invalid <dir> '" + dir + "': it must name a subdirectory, not '.' or '/'");
        }
        return explodedClasspath(name.toString(), dirPath);
    }

    /**
     * Resolves the Maven project being built at compile scope — {@code target/classes} plus its compile- and
     * runtime-scoped dependency jars. {@return its {@link Classpath}}.
     */
    Classpath resolveCurrentProject() {
        return new Classpath(
                project.getArtifactId(), List.of(basePath.resolve("target/classes")), dependencyJars(false));
    }

    /**
     * Resolves the Maven project being built at test scope — {@code target/classes} and
     * {@code target/test-classes} plus its test-scoped dependency jars. {@return its {@link Classpath}}.
     */
    Classpath resolveCurrentProjectTests() {
        var target = basePath.resolve("target");
        return new Classpath(
                project.getArtifactId() + "-tests",
                List.of(target.resolve("classes"), target.resolve("test-classes")),
                dependencyJars(true));
    }

    /** {@return the {@code artifactId-version} output-dir name} for a GAV coordinate. */
    private static String artifactOutputDir(String gav) {
        var parts = gav.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Malformed artifact coordinate (expected groupId:artifactId[:...]:version): " + gav);
        }
        return parts[1] + "-" + parts[parts.length - 1];
    }

    /**
     * Resolves a Maven artifact from a GAV coordinate, unpacks it to a staging directory named after
     * {@code outputDir}, and {@return that directory}.
     */
    private Path unpackArtifact(String gav, String outputDir) throws ArtifactResolutionException, IOException {
        log.info("Resolving artifact: " + gav);

        var artifact = new DefaultArtifact(gav);
        var request = new ArtifactRequest(artifact, project.getRemoteProjectRepositories(), null);
        var resolved = repositorySystem.resolveArtifact(session.getRepositorySession(), request);

        var stagingDir = basePath.resolve("target")
                .resolve("opendst-package")
                .resolve("staging")
                .resolve(outputDir);
        unpackArchive(resolved.getArtifact().getFile().toPath(), stagingDir);
        return stagingDir;
    }

    /**
     * {@return the {@link Classpath} inside an exploded WAR/artifact {@code directory}} — every
     * {@code classes} directory and every jar, which is exactly what the runtime classloader loads
     * ({@code WEB-INF/classes}, {@code WEB-INF/lib/*.jar}). Arbitrary other files (a {@code web.xml},
     * static assets) are not on the classpath and are ignored.
     */
    private static Classpath explodedClasspath(String outputDir, Path directory) throws IOException {
        var classDirs = new ArrayList<Path>();
        var jars = new ArrayList<Path>();
        try (var tree = walk(directory)) {
            tree.forEach(p -> {
                if (isDirectory(p) && p.getFileName().toString().equals("classes")) {
                    classDirs.add(p);
                } else if (p.toString().endsWith(".jar")) {
                    jars.add(p);
                }
            });
        }
        return new Classpath(outputDir, classDirs, jars);
    }

    /**
     * {@return the project's dependency JARs}, excluding {@code opendst-sdk} (compile-only — its classes
     * are redirected by instrumentation). Compile- and runtime-scoped artifacts are always included;
     * test-scoped ones only when {@code includeTest}.
     */
    private List<Path> dependencyJars(boolean includeTest) {
        var jars = new ArrayList<Path>();
        for (var artifact : project.getArtifacts()) {
            var artifactScope = artifact.getScope();
            if (!Artifact.SCOPE_COMPILE.equals(artifactScope)
                    && !Artifact.SCOPE_RUNTIME.equals(artifactScope)
                    && !(includeTest && Artifact.SCOPE_TEST.equals(artifactScope))) {
                continue;
            }
            if ("opendst-sdk".equals(artifact.getArtifactId())
                    && "com.pingidentity.opendst".equals(artifact.getGroupId())) {
                continue;
            }
            var jarFile = artifact.getFile();
            if (jarFile != null && jarFile.getName().endsWith(".jar")) {
                jars.add(jarFile.toPath());
            }
        }
        return jars;
    }

    /**
     * Unpacks an archive (WAR or JAR) into the given target directory, producing an exploded layout
     * with {@code WEB-INF/classes/} and {@code WEB-INF/lib/}.
     */
    private static void unpackArchive(Path archiveFile, Path targetDir) throws IOException {
        createDirectories(targetDir);
        try (var archiveFs = newFileSystem(archiveFile, (ClassLoader) null)) {
            var root = archiveFs.getPath("/");
            try (var stream = walk(root)) {
                for (var entry : stream.toList()) {
                    var relativePath = root.relativize(entry).toString();
                    if (relativePath.isEmpty()) {
                        continue;
                    }
                    var target = targetDir.resolve(relativePath).normalize();
                    if (!target.startsWith(targetDir.normalize())) {
                        throw new IOException(
                                "Zip-Slip: entry '" + relativePath + "' resolves outside target directory");
                    }
                    if (isDirectory(entry)) {
                        createDirectories(target);
                    } else {
                        createDirectories(target.getParent());
                        copy(entry, target, REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * A resolved app: the {@code apps/} subdirectory its instrumented content is written to, plus the class
     * directories and jars to instrument, split by kind (class dirs merge into {@code WEB-INF/classes}, each
     * jar lands in {@code WEB-INF/lib}). {@link Instrumentation} rewrites the bytecode; the {@code outputDir}
     * is both the jar layout ({@code apps/<outputDir>}) and the dedup key so a shared source is instrumented
     * once.
     */
    record Classpath(String outputDir, List<Path> classDirs, List<Path> jars) {

        /** {@return a new {@link URLClassLoader} over this classpath's entries}, delegating to {@code parent}. */
        URLClassLoader newClassLoader(ClassLoader parent) throws MalformedURLException {
            var urls = new ArrayList<URL>();
            for (var dir : classDirs) {
                urls.add(dir.toUri().toURL());
            }
            for (var jar : jars) {
                urls.add(jar.toUri().toURL());
            }
            return new URLClassLoader(urls.toArray(URL[]::new), parent);
        }
    }
}
