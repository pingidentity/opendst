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

import static java.io.File.pathSeparator;
import static java.lang.Long.MAX_VALUE;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.exit;
import static java.lang.System.getProperty;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;
import static java.nio.file.Files.copy;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.asList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.DAYS;

import com.pingidentity.opendst.maven.Orchestrator.Plan;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import tools.jackson.jr.ob.JSON;

@Mojo(
        name = "run-test",
        defaultPhase = LifecyclePhase.INTEGRATION_TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
public class ContinuousTestMojo extends AbstractMojo {
    private Orchestrator orchestrator;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "opendst.testClass", required = true)
    private String testClass;

    @Parameter(property = "opendst.testMethod", required = true)
    private String testMethod;

    @Parameter(property = "opendst.parallelism", defaultValue = "1")
    private int parallelism;

    @Parameter(property = "opendst.jvmArguments")
    private String jvmArguments;
    /**
     * Arguments for the JVM debug agent (e.g. -agentlib:jdwp=...).
     * If set to "true" or empty, uses default settings.
     * If set to a string, uses that string as the arguments.
     */
    @Parameter(property = "opendst.debug")
    private String debug;

    @Parameter(property = "opendst.signalPatterns")
    private List<String> signalPatterns;

    @Parameter(property = "opendst.failurePatterns")
    private List<String> failurePatterns;

    @Parameter(property = "opendst.replayProbability", defaultValue = "0.05")
    private double replayProbability;

    @Parameter(property = "opendst.plan")
    private File planFile;

    @Parameter(property = "skipTests")
    private boolean skipTests;

    private File getPluginJar() {
        try {
            return new File(MethodRunner.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (Exception e) {
            getLog().warn("Could not locate plugin jar", e);
            return null;
        }
    }

    private boolean isDebug() {
        return debug != null && !debug.equalsIgnoreCase("false");
    }

    private boolean isReplay() {
        return planFile != null;
    }

    private String getDebugArgs() {
        if (!isDebug()) {
            return null;
        }
        if (debug.isEmpty() || debug.equalsIgnoreCase("true")) {
            // Default debug arguments.
            return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005";
        }
        return debug;
    }

    private String agentJarPath;

    private synchronized String getAgentJarPath() throws MojoExecutionException {
        if (agentJarPath != null) {
            return agentJarPath;
        }
        try (var is = getClass().getResourceAsStream("/META-INF/agents/opendst-simulator.jar")) {
            if (is == null) {
                throw new MojoExecutionException("Could not find embedded opendst-simulator.jar");
            }
            var agentJar = new File("opendst-simulator.jar");
            agentJar.deleteOnExit();
            copy(is, agentJar.toPath(), REPLACE_EXISTING);
            agentJarPath = agentJar.getAbsolutePath();
            return agentJarPath;
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to extract opendst-simulator.jar", e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (skipTests) {
            getLog().info("Skipping opendst-maven-plugin execution because -DskipTests is enabled.");
            return;
        }

        // Allow command line -Dopendst.parallelism to override pom.xml configuration
        // unless debug is enabled, which forces it to 1
        if (isDebug()) {
            getLog().info("Debug mode enabled. Forcing parallelism to 1 and enabling JVM debug agent.");
            this.parallelism = 1;
        } else {
            var cliParallelism = getProperty("opendst.parallelism");
            if (cliParallelism != null) {
                try {
                    this.parallelism = Integer.parseInt(cliParallelism);
                    getLog().info("Parallelism overridden via command line: " + this.parallelism);
                } catch (NumberFormatException e) {
                    getLog().warn("Invalid value for opendst.parallelism: " + cliParallelism);
                }
            }
        }

        getLog().info("OpenDST testing ...");
        getLog().info("  Class: " + testClass);
        getLog().info("  Method: " + testMethod);
        getLog().info("  Parallelism: " + parallelism);
        if (isReplay()) {
            getLog().info("  Mode: Replay (Single run)");
            getLog().info("  Plan: " + planFile);
            getLog().info("  Debug Args: " + getDebugArgs());
        } else if (isDebug()) {
            getLog().info("  Mode: Debug (Single run)");
            getLog().info("  Debug Args: " + getDebugArgs());
        }

        orchestrator = isReplay()
                ? new ReplayOrchestrator(JSON.std.beanFrom(Plan.class, planFile))
                : new AcoOrchestrator(project.getBasedir(), signalPatterns, failurePatterns);
        try (var executor = newFixedThreadPool(parallelism)) {
            var classpathElements = new ArrayList<>(project.getRuntimeClasspathElements());
            // TODO: Ultimately, we should have only add the MethodRunner class on the classpath.
            classpathElements.add(getPluginJar().getAbsolutePath());
            var classpath = String.join(pathSeparator, classpathElements);
            for (int i = 0; i < parallelism; i++) {
                executor.submit(() -> runLoop(classpath));
            }
            // For debug mode, we might want to wait for the task to finish naturally?
            // But main thread waiting forever is fine if we shut down executor after tasks.
            executor.shutdown(); // Stop accepting new tasks
            // Wait for termination. If looping, this waits forever. If debug, this waits for runOnce.
            executor.awaitTermination(MAX_VALUE, DAYS);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve test classpath", e);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupted while waiting for test execution", e);
        }
    }

    private void runLoop(String classpath) {
        if (isDebug() | isReplay()) {
            try {
                // In debug, we just run once with a fresh plan
                runOnce(classpath, orchestrator.nextPlan());
            } catch (Exception e) {
                getLog().error("Error in debug test run", e);
            }
            return;
        }

        while (!currentThread().isInterrupted()) {
            try {
                runOnce(classpath, orchestrator.nextPlan());

                // For now we don't have the feedback loop fully closed with planExecuted
                // because we need to extract lastIteration/last from logs
                // orchestrator.planExecuted(plan.rid(), ...);
            } catch (Exception e) {
                getLog().error("Error in test run", e);
                try {
                    sleep(1000);
                } catch (InterruptedException interruptedException) {
                    currentThread().interrupt();
                }
            }
        }
    }

    private void runOnce(String classpath, Plan plan) {
        var javaBin = Path.of(getProperty("java.home"), "bin", "java");
        var command = new ArrayList<String>();
        command.add(javaBin.toString());
        try {
            command.add("-javaagent:" + getAgentJarPath());
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
            command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            command.add("-Dnet.bytebuddy.safe=true");
            command.add("-XX:+UnlockExperimentalVMOptions");
            command.add("-XX:hashCode=2");
        } catch (MojoExecutionException e) {
            if (isDebug()) {
                getLog().warn("Could not load agent: " + e.getMessage());
            } else {
                throw new RuntimeException(e);
            }
        }

        if (jvmArguments != null && !jvmArguments.isBlank()) {
            command.addAll(asList(jvmArguments.split("\\s+")));
        }
        if (isDebug()) {
            // Split args to handle multiple flags if passed by user
            command.addAll(asList(getDebugArgs().split("\\s+")));
        }
        command.addAll(asList("-cp", classpath, "com.pingidentity.opendst.maven.MethodRunner", testClass, testMethod));

        int code = -1;
        try {
            var process = new ProcessBuilder(command)
                    .directory(project.getBasedir())
                    .redirectErrorStream(true)
                    .start();
            var shutdown = new Thread(process::destroyForcibly);
            getRuntime().addShutdownHook(shutdown);
            JSON.std.write(plan, process.outputWriter());
            try (var console = process.inputReader()) {
                for (var line = console.readLine(); line != null; line = console.readLine()) {
                    orchestrator.onLogReceived(plan, line);
                }
            }
            code = process.waitFor();
            getRuntime().removeShutdownHook(shutdown);
        } catch (Exception e) {
            e.printStackTrace();
            exit(1);
        } finally {
            orchestrator.onPlanTerminated(plan, code);
            if (code != 0) {
                exit(code);
            }
        }
    }
}
