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
package com.pingidentity.opendst.runner;

import static com.pingidentity.opendst.common.Assertion.NO_INTERNAL_ERROR;
import static com.pingidentity.opendst.common.Assertion.NO_TRACE_AUDITOR_EXCEPTION;
import static com.pingidentity.opendst.common.Assertion.NO_UNCAUGHT_EXCEPTION;
import static com.pingidentity.opendst.common.Assertion.SIMULATION_STARTED;
import static com.pingidentity.opendst.common.Assertion.SIMULATION_TERMINATED;
import static com.pingidentity.opendst.runner.Commons.JAVA_BASE_OPTIONS;
import static com.pingidentity.opendst.runner.Commons.JSON_OBJECT;
import static com.pingidentity.opendst.runner.Commons.deleteRecursively;
import static com.pingidentity.opendst.runner.Logger.ofConsole;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.lang.Thread.ofVirtual;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.util.Arrays.asList;
import static java.util.concurrent.ThreadLocalRandom.current;

import com.pingidentity.opendst.common.Assertion;
import com.pingidentity.opendst.common.Plan;
import com.pingidentity.opendst.common.RuntimeDeployment;
import com.pingidentity.opendst.common.Signal;
import com.pingidentity.opendst.common.SimulationEvent;
import com.pingidentity.opendst.runner.Planner.ExecutionPlan;
import com.pingidentity.opendst.runner.Planner.GuidedPlanner;
import com.pingidentity.opendst.runner.Planner.ReplayPlanner;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.core.JacksonException;

/**
 * Planner entry point for the self-contained JAR. Invoked reflectively by {@link Bootstrap} after extraction and
 * classloader setup; the first argument is always the working directory path (prepended by the launcher).
 *
 * <p>This class is both the picocli command (top: {@code @Option} fields, {@link #main} and {@link #call}) and the
 * parent-side simulation driver (bottom: per-fork run loop, child JVM spawning, signal monitoring). The run-loop
 * methods read derived state set up by {@link #call} before forking.
 *
 * <p>The working directory has the following structure:
 * <pre>
 *   &lt;workingDir&gt;/
 *     deployment/     — extracted JAR contents (configs, classes, system JARs, apps)
 *       META-INF/opendst/{assertions.json, deployment.json}
 *       system/*.jar  — child JVM classpath (incl. opendst-agent.jar, opendst-patch.jar)
 *       apps/         — instrumented application JARs
 *     runs/           — ephemeral per-fork directories (created/deleted each run)
 *     report/         — simulation output (persists across runs)
 *       report.json
 *       plans/        — execution plan files (one per distinct plan hash)
 * </pre>
 */
@Command(name = "opendst", description = "Run OpenDST deterministic simulation tests", mixinStandardHelpOptions = true)
public final class RunnerCli implements Callable<Integer> {

    /** Number of consecutive infrastructure crashes before aborting all forks. */
    private static final int MAX_CONSECUTIVE_CRASHES = 3;

    /** How often (in runs) to emit a progress line to the console. */
    private static final int PROGRESS_INTERVAL = 10;

    // ------------------------------------------------------------------
    // CLI options (populated by picocli via reflection)
    // ------------------------------------------------------------------

    @Parameters(index = "0", hidden = true, description = "Working directory (set by Bootstrap)")
    private Path workingDir;

    @Option(
            names = "--stop",
            converter = StopCondition.Converter.class,
            description =
                    "Early-stopping conditions (combinable): any-fail, all-pass. Default: none (run until stagnation)")
    private Set<StopCondition> stopConditions;

    @Option(
            names = "--fork-count",
            description = "Number of concurrent simulation forks. Supports a 'C' suffix for CPU-relative"
                    + " values (e.g. '1C' = all cores, '0.5C' = half). Plain integers are used"
                    + " as-is. Default: max(1, CPUs/2 - 1)")
    private String forkCountSpec;

    @Option(
            names = "--duration",
            description = "Maximum number of simulation steps per execution",
            defaultValue = "100000")
    private long duration;

    @Option(
            names = "--branch-probability",
            description = "Probability of branching to explore a new path",
            defaultValue = "0.7")
    private double branchProbability;

    @Option(
            names = "--replay-probability",
            description = "Probability of replaying a previous trace",
            defaultValue = "0.05")
    private double replayProbability;

    @Option(
            names = "--stagnation-limit",
            description = "Stop after this many iterations without new coverage",
            defaultValue = "100")
    private int stagnationLimit;

    @Option(names = "--plan", description = "Replay a saved plan file instead of exploring")
    private Path planFile;

    @Option(names = "--extra-jvm-args", description = "Additional JVM arguments appended to build-time defaults")
    private String extraJvmArgs;

    @Option(
            names = "--debug",
            arity = "0..1",
            fallbackValue = "5005",
            description = "Enable JDWP remote debugging on the forked JVM and verbose logging"
                    + " (default address: ${FALLBACK-VALUE})")
    private String debugAddress;

    // ------------------------------------------------------------------
    // Run-loop state (initialized at the start of call() before forking)
    // ------------------------------------------------------------------

    private Path deploymentDir;
    private Path reportDir;
    private Path runsDir;
    private Logger logger;
    private Planner planner;
    private String effectiveJvmArgs;
    private String debugArgs;
    private Set<StopCondition> effectiveStopConditions;
    private int forkCount;
    private boolean isReplay;
    private boolean isDebugOrReplay;

    private final AtomicInteger runSequence = new AtomicInteger();
    private final AtomicInteger boringRunStreak = new AtomicInteger();
    private final Queue<Plan> pastPlans = new ArrayBlockingQueue<>(10);
    private final AtomicBoolean earlyExit = new AtomicBoolean();

    // ==================================================================
    // CLI lifecycle
    // ==================================================================

    public static void main(String[] args) {
        var cmd = new CommandLine(new RunnerCli());
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        exit(cmd.execute(args));
    }

    @Override
    public Integer call() throws Exception {
        boolean isDebug = debugAddress != null;
        forkCount = resolveForkCount(forkCountSpec);

        // Derive directory layout from working directory
        deploymentDir = workingDir.resolve("deployment");
        reportDir = workingDir.resolve("report");
        runsDir = workingDir.resolve("runs");
        createDirectories(reportDir.resolve("plans"));
        createDirectories(runsDir);

        // 1. Load assertions
        var assertionsFile = deploymentDir.resolve("META-INF/opendst/assertions.json");
        Set<Assertion> discoveredAssertions =
                Set.copyOf(JSON_OBJECT.listOfFrom(Assertion.class, assertionsFile.toFile()));

        // Merge discovered assertions with built-in lifecycle assertions
        var assertions = new LinkedHashSet<>(discoveredAssertions);
        assertions.add(SIMULATION_STARTED);
        assertions.add(SIMULATION_TERMINATED);
        assertions.add(NO_UNCAUGHT_EXCEPTION);
        assertions.add(NO_TRACE_AUDITOR_EXCEPTION);
        assertions.add(NO_INTERNAL_ERROR);

        // 2. Load runtime deployment
        var deploymentFile = deploymentDir.resolve("META-INF/opendst/deployment.json");
        var deployment = JSON_OBJECT.beanFrom(RuntimeDeployment.class, deploymentFile.toFile());

        // 3. Set up logger and JVM wiring
        logger = ofConsole(isDebug);
        if (isDebug) {
            logger.raw()
                    .info(logger.colored(
                            Logger.CYAN,
                            "\uD83D\uDC1B Debug mode enabled. Attach debugger to address " + debugAddress
                                    + " (suspend=y)"));
        }
        var faultsConfig = Planner.DEFAULT_FAULTS;

        verifyPatchModuleJdkVersion(deploymentDir.resolve("system/opendst-patch.jar"), logger);

        // Merge JVM arguments: build-time defaults + CLI --extra-jvm-args (additive)
        var buildTimeArgs = deployment.jvmArguments();
        effectiveJvmArgs = buildTimeArgs != null && extraJvmArgs != null
                ? buildTimeArgs + " " + extraJvmArgs
                : buildTimeArgs != null ? buildTimeArgs : extraJvmArgs;

        debugArgs = isDebug ? "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + debugAddress : null;

        // 4. Pick planner: replay mode loads a saved plan, otherwise explore
        isReplay = planFile != null;
        if (isReplay) {
            var plan = JSON_OBJECT.beanFrom(Plan.class, planFile.toFile());
            planner = new ReplayPlanner(plan);
            forkCount = 1;
        } else {
            planner = new GuidedPlanner(logger, duration, branchProbability, faultsConfig);
        }
        if (isDebug) {
            forkCount = 1;
        }
        isDebugOrReplay = isReplay || isDebug;

        effectiveStopConditions =
                stopConditions != null ? EnumSet.copyOf(stopConditions) : EnumSet.noneOf(StopCondition.class);

        logger.run("settings")
                .with("duration", duration)
                .with("forks", forkCount)
                .with("branch", "%.2f".formatted(branchProbability))
                .with("replay", "%.2f".formatted(replayProbability))
                .with("stagnation", stagnationLimit)
                .with(
                        "stop",
                        effectiveStopConditions.isEmpty()
                                ? "none"
                                : effectiveStopConditions.stream()
                                        .map(s -> s.name().toLowerCase().replace('_', '-'))
                                        .collect(Collectors.joining(",")))
                .log();

        // 5. Run the parallel fork loops
        var reportGenerator = new ReportGenerator(assertions, reportDir);
        runForks(reportGenerator);

        // 6. Final reporting
        if (isReplay) {
            logger.raw().info(logger.colored(Logger.GREEN, "\uD83D\uDD01 Replay complete."));
            return 0;
        }

        // Always write a final report — even if no run was "interesting", we still
        // want a summary of whatever happened during the simulation.
        var reportFile = reportDir.resolve("report.json");
        reportGenerator.generate(reportFile);

        if (reportGenerator.hasFailures()) {
            logger.raw()
                    .error(logger.colored(
                            Logger.RED + Logger.BOLD,
                            "\u274C Simulation complete with failures. Report: " + reportFile));
            return 1;
        }
        logger.raw()
                .info(logger.colored(
                        Logger.GREEN, "\u2705 Simulation complete. All assertions passed. Report: " + reportFile));
        return 0;
    }

    // ==================================================================
    // Parent-side simulation driver: per-fork run loops
    // ==================================================================

    /** Spawns {@link #forkCount} virtual threads, each running its own {@link #runLoop}, and waits for them. */
    private void runForks(ReportGenerator reportGenerator) throws Exception {
        var futures = new ArrayList<Future<Void>>();
        for (int i = 0; i < forkCount; i++) {
            int count = i;
            var future = new FutureTask<>(() -> runLoop(count, reportGenerator));
            ofVirtual().name("opendst-executor-" + i).start(future);
            futures.add(future);
        }
        for (var future : futures) {
            future.get();
        }
    }

    private Void runLoop(int count, ReportGenerator reportGenerator) throws IOException, InterruptedException {
        int consecutiveCrashes = 0;
        for (; ; ) {
            if (earlyExit.get()) {
                return null;
            }
            var executionPlan = getNewExecutionPlanOrReplay();
            int runCount = runSequence.incrementAndGet();

            var runBaseDir = createRunBaseDir(count);
            RunResult runResult;
            try {
                runResult = runOnce(runBaseDir, executionPlan);
            } catch (IOException | InterruptedException e) {
                deleteRecursively(runsDir, runBaseDir);
                throw e;
            }

            // Detect infrastructure crashes (child JVM dies before the simulator starts).
            // If this happens repeatedly, the classpath or environment is broken — continuing
            // would just rapid-fire spawn doomed processes across all forks.
            if (runResult.isInfrastructureCrash()) {
                consecutiveCrashes++;
                if (consecutiveCrashes >= MAX_CONSECUTIVE_CRASHES) {
                    logger.raw()
                            .error("Child JVM crashed %d times consecutively without starting the simulation — aborting"
                                    .formatted(consecutiveCrashes));
                    earlyExit.set(true);
                    deleteRecursively(runsDir, runBaseDir);
                    return null;
                }
            } else {
                consecutiveCrashes = 0;
            }

            if (isDebugOrReplay) {
                deleteRecursively(runsDir, runBaseDir);
                return null;
            }
            if (runResult.runFailed()) {
                logger.run("fail").error().withHash(runResult.runHash()).log();
            } else if (executionPlan.plan().hash() == 0) {
                pastPlans.offer(executionPlan.plan().withHash(runResult.runHash()));
            } else if (executionPlan.plan().hash() == runResult.runHash()) {
                logger.run("verified").withHash(runResult.runHash()).log();
            }

            var hexHash = HexFormat.of().toHexDigits(runResult.runHash());
            var planFile = reportDir.resolve("plans").resolve(hexHash + ".plan.json");
            savePlan(executionPlan.plan().withHash(runResult.runHash()), planFile);
            reportGenerator.addRunResult(runResult, planFile);

            // Run directory is ephemeral — clean it up; nothing to preserve from disk
            // (signals were streamed to the parent over stdout, not written to a file).
            deleteRecursively(runsDir, runBaseDir);

            if (effectiveStopConditions.contains(StopCondition.ANY_FAIL)
                    && reportGenerator.hasFailures()
                    && earlyExit.compareAndSet(false, true)) {
                logger.raw().warn("Assertion failure detected \u2014 stopping (--stop any-fail)");
            }
            if (effectiveStopConditions.contains(StopCondition.ALL_PASS)
                    && runCount >= stagnationLimit
                    && reportGenerator.allPassed()
                    && earlyExit.compareAndSet(false, true)) {
                logger.raw().info("All assertions passing \u2014 stopping (--stop all-pass)");
            }

            if (runResult.isInteresting()) {
                reportGenerator.generate(reportDir.resolve("report.json"));
                boringRunStreak.set(0);
            } else if (boringRunStreak.incrementAndGet() >= stagnationLimit) {
                return null;
            }

            if (runCount % PROGRESS_INTERVAL == 0) {
                var counts = reportGenerator.passingCount();
                logger.run("progress")
                        .with("run", runCount)
                        .with("stagnation", boringRunStreak.get())
                        .withPassing(counts[0], counts[1])
                        .log();
            }
        }
    }

    private ExecutionPlan getNewExecutionPlanOrReplay() {
        if (!isReplay && current().nextDouble() < replayProbability) {
            var plan = pastPlans.poll();
            if (plan != null) {
                logger.run("check").withHash(plan.hash()).log();
                return new ExecutionPlan(plan, _ -> false);
            }
        }
        return planner.nextPlan();
    }

    private RunResult runOnce(Path runBaseDir, ExecutionPlan execution) throws IOException, InterruptedException {
        Process proc = null;
        Thread runKiller = null;
        try {
            proc = startProcess(runBaseDir);
            runKiller = new Thread(proc::destroyForcibly);
            getRuntime().addShutdownHook(runKiller);
            JSON_OBJECT.write(execution.plan(), proc.getOutputStream());
            return monitorExecutionOutput(proc, execution);
        } finally {
            if (proc != null) {
                // Graceful SIGTERM lets the child JVM run shutdown hooks.
                // Falls back to SIGKILL if the child doesn't exit within 30 seconds.
                proc.destroy();
                if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            }
            if (runKiller != null) {
                try {
                    getRuntime().removeShutdownHook(runKiller);
                } catch (IllegalStateException ignored) {
                    // JVM is shutting down; hook will run naturally
                }
            }
        }
    }

    private Process startProcess(Path runBaseDir) throws IOException {
        return new ProcessBuilder(buildChildCommandLine(runBaseDir))
                .directory(runBaseDir.toFile())
                .redirectErrorStream(true)
                .start();
    }

    private RunResult monitorExecutionOutput(Process proc, ExecutionPlan execution)
            throws IOException, InterruptedException {
        var lastLogs = new ArrayDeque<String>();
        var result = new RunResult();
        try (var logs = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            for (var line = logs.readLine(); line != null; line = logs.readLine()) {
                if (lastLogs.size() >= 50) {
                    lastLogs.removeFirst();
                }
                lastLogs.addLast(line);
                SimulationEvent event = parseEvent(line);
                if (event != null) {
                    boolean interestingEvent = execution.interesting().test(event);
                    if (result.addSignal(event, interestingEvent)) {
                        return result;
                    }
                }
            }
            // Child exited without sending "stopped" — synthesize an error result so the failure
            // flows through the normal reporting pipeline (fail line, report.json).
            int exitCode = proc.waitFor();
            result.synthesizeCrash(exitCode, lastLogs);
            logger.raw()
                    .error("child process exited unexpectedly (code %d). Last %d log lines preserved in report."
                            .formatted(exitCode, lastLogs.size()));
            return result;
        }
    }

    /**
     * Parses one wire line into a {@link SimulationEvent}, or returns {@code null} if the line is
     * not structured JSON, has an unknown signal type, or fails to parse. The polymorphic
     * {@link Signal} dispatch is handled by the value reader registered in {@link Commons}.
     */
    private SimulationEvent parseEvent(String line) {
        if (isDebugOrReplay) {
            out.println(line);
        }
        if (!isJson(line)) {
            return null;
        }
        try {
            var event = JSON_OBJECT.beanFrom(SimulationEvent.class, line);
            return event != null && event.signal() != null ? event : null;
        } catch (JacksonException e) {
            if (!isDebugOrReplay) {
                logger.raw().debug(line);
            }
            return null;
        }
    }

    private Path createRunBaseDir(int count) throws IOException {
        var runBaseDir =
                runsDir.resolve(Commons.RUN_DIR_FORMAT.formatted(count)).toAbsolutePath();
        deleteRecursively(runsDir, runBaseDir);
        if (!runBaseDir.toFile().mkdirs()) {
            throw new IllegalStateException(
                    "The run base dir '%s' is unexpectedly already present".formatted(runBaseDir));
        }
        return runBaseDir;
    }

    private static void savePlan(Plan plan, Path file) throws IOException {
        try (var os = newOutputStream(file, CREATE_NEW)) {
            JSON_OBJECT.write(plan, os);
        } catch (FileAlreadyExistsException e) {
            // Ignore: This plan has already been saved
        }
    }

    private static boolean isJson(String line) {
        return line.length() > 2 && line.charAt(0) == '{' && line.charAt(line.length() - 1) == '}';
    }

    // ==================================================================
    // Child JVM wiring
    // ==================================================================

    /**
     * Builds the {@code java ...} command line for a child JVM rooted at {@code runBaseDir}.
     * Each run gets its own {@code tmp} directory; the agent creates per-node subdirectories
     * inside it when temp files are requested.
     *
     * <p>Layout under {@link #deploymentDir} is fixed by convention:
     * {@code system/*.jar} on the classpath, {@code system/opendst-agent.jar} as the
     * Java agent, {@code system/opendst-patch.jar} as the {@code java.base} patch
     * module, {@code apps/} as the instrumented application directory.
     */
    private List<String> buildChildCommandLine(Path runBaseDir) {
        var runTmpDir = runBaseDir.resolve("tmp");
        if (!runTmpDir.toFile().mkdirs()) {
            throw new IllegalStateException("The run tmp directory already exists: %s".formatted(runBaseDir));
        }
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var agentJar = deploymentDir.resolve("system/opendst-agent.jar").toAbsolutePath();
        var patchJar = deploymentDir.resolve("system/opendst-patch.jar").toAbsolutePath();
        var command = new ArrayList<String>();
        command.add(javaBin);
        command.addAll(JAVA_BASE_OPTIONS);
        // --patch-module allows Thread subclasses to run as virtual threads by injecting
        // SimulatorThread (extends non-final VirtualThread) into java.base.
        command.add("--patch-module");
        command.add("java.base=%s".formatted(patchJar));
        command.add("-javaagent:%s".formatted(agentJar));
        if (effectiveJvmArgs != null && !effectiveJvmArgs.isBlank()) {
            command.addAll(asList(effectiveJvmArgs.split(" +")));
        }
        if (debugArgs != null) {
            command.addAll(asList(debugArgs.split(" +")));
        }
        command.addAll(asList(
                "-cp",
                buildChildClasspath(deploymentDir),
                // FQN of the child JVM entry point. Hard-coded as a string rather than
                // {@code SimulationLauncher.class.getName()} because {@code SimulationLauncher}
                // lives in {@code opendst-agent} (so the agent's existing jackson-jr can parse
                // the deployment descriptor without dragging jackson-databind into the agent JAR),
                // and adding a compile dependency on the agent here would defeat that goal.
                "com.pingidentity.opendst.SimulationLauncher",
                deploymentDir.toAbsolutePath().toString()));
        return List.copyOf(command);
    }

    /** Builds the classpath for child JVMs from all library JARs in {@code deployment/system/}. */
    private static String buildChildClasspath(Path deploymentDir) {
        var sb = new StringBuilder();
        var systemDir = deploymentDir.resolve("system").toFile();
        var jars = systemDir.listFiles((_, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (int i = 0; i < jars.length; i++) {
                if (i > 0) sb.append(File.pathSeparatorChar);
                sb.append(jars[i].getAbsolutePath());
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Resolves the {@code --fork-count} specification into a concrete thread count.
     *
     * <p>Accepts three forms:
     * <ul>
     *   <li>{@code null} or empty — default: {@code max(1, availableProcessors/2 - 1)}</li>
     *   <li>A plain integer (e.g. {@code "4"}) — used as-is, clamped to min 1</li>
     *   <li>A CPU-relative value with {@code C} suffix (e.g. {@code "0.5C"}) —
     *       {@code max(1, floor(multiplier * availableProcessors))}</li>
     * </ul>
     */
    private static int resolveForkCount(String spec) {
        int cores = Runtime.getRuntime().availableProcessors();
        if (spec == null || spec.isEmpty()) {
            return Math.max(1, cores / 2 - 1);
        }
        if (spec.endsWith("C") || spec.endsWith("c")) {
            double multiplier = Double.parseDouble(spec.substring(0, spec.length() - 1));
            return Math.max(1, (int) (multiplier * cores));
        }
        return Math.max(1, Integer.parseInt(spec));
    }

    /**
     * Verifies that the JDK used at build time (recorded in {@code opendst-patch.jar}'s MANIFEST)
     * matches the current runtime JDK. The patch JAR contains a byte-for-byte copy of
     * {@code VirtualThread.class} from the build JDK, so a version mismatch would cause
     * unpredictable failures at runtime.
     */
    private static void verifyPatchModuleJdkVersion(Path patchJar, Logger logger) {
        try (var jar = new JarFile(patchJar.toFile())) {
            var buildVersion = jar.getManifest().getMainAttributes().getValue("Build-Jdk-Version");
            var buildMajor = Runtime.Version.parse(buildVersion).feature();
            var runtimeMajor = Runtime.version().feature();
            if (buildMajor != runtimeMajor) {
                throw new IllegalStateException(
                        "JDK version mismatch: opendst-patch.jar was built with JDK %s but running on JDK %s. "
                                        .formatted(buildVersion, Runtime.version())
                                + "The patch JAR contains VirtualThread.class from the build JDK and is incompatible "
                                + "with a different JDK version. Rebuild with the current JDK.");
            }
            if (!buildVersion.equals(Runtime.version().toString())) {
                logger.raw()
                        .warn("opendst-patch.jar was built with JDK %s but running on JDK %s. "
                                        .formatted(buildVersion, Runtime.version())
                                + "Consider rebuilding to match exactly.");
            }
        } catch (IOException e) {
            logger.raw().warn("Could not read opendst-patch.jar manifest: " + e.getMessage());
        }
    }

    /**
     * Early-stopping conditions for the run loop, settable via repeated {@code --stop} CLI flags.
     *
     * <ul>
     *   <li>{@link #ANY_FAIL} — stop immediately on the first assertion failure</li>
     *   <li>{@link #ALL_PASS} — stop early once all assertions are passing,
     *       after at least {@code --stagnation-limit} executions for confidence</li>
     * </ul>
     *
     * <p>When no {@code --stop} flag is given, the runner runs until the stagnation
     * limit is reached (no early stopping).
     */
    public enum StopCondition {
        ANY_FAIL,
        ALL_PASS;

        /** Picocli converter that accepts CLI values like {@code any-fail} or {@code ANY_FAIL}. */
        static final class Converter implements CommandLine.ITypeConverter<StopCondition> {
            @Override
            public StopCondition convert(String value) {
                return StopCondition.valueOf(value.toUpperCase().replace('-', '_'));
            }
        }
    }
}
