/*
 * Copyright 2024-2026 Ping Identity Corporation
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
package com.pingidentity.opendst;

import static com.pingidentity.opendst.SimulatorAgent.AGENT_PROPERTY;
import static com.pingidentity.opendst.VirtualThreadInternals.clearNext;
import static com.pingidentity.opendst.VirtualThreadInternals.compareAndSetOnWaitingList;
import static com.pingidentity.opendst.VirtualThreadInternals.getNext;
import static com.pingidentity.opendst.VirtualThreadInternals.getThreadLocal;
import static com.pingidentity.opendst.VirtualThreadInternals.isOnWaitingList;
import static com.pingidentity.opendst.VirtualThreadInternals.setThreadLocal;
import static com.pingidentity.opendst.VirtualThreadInternals.takeVirtualThreadListToUnblock;
import static com.pingidentity.opendst.VirtualThreadInternals.unblock;
import static java.lang.Boolean.getBoolean;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.System.err;
import static java.lang.System.getProperty;
import static java.lang.Thread.State.TERMINATED;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofVirtual;
import static java.lang.Thread.onSpinWait;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.walkFileTree;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.time.Duration.ofSeconds;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.util.Locale.ROOT;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.Future.State.SUCCESS;

import com.pingidentity.opendst.Simulator.Plan.Segment;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serial;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import tools.jackson.jr.ob.JSON;

/**
 * Implements a deterministic execution environment.
 * <p>
 * To provide a deterministic execution environment, the non-deterministic Java API are transparently replaced by
 * simulated version which provide deterministic execution. Given the same inputs, the execution history of the
 * scenario provided to {@link #runSimulation(Callable)}} will always be the same.
 * <p>
 * While most of the work to replace non-deterministic API is done transparently thanks to the {@link SimulatorAgent}
 * there are still some limitations which might require the tested application to be modified in order to behave
 * deterministically:
 * <ul>
 *     <li>extends {@link Thread}: Inheriting from {@link Thread} is not supported. The application code must be
 *     rewritten to use composition rather than inheritance</li>
 *     <li>Network: Socket are not compatible with the simulated network</li>*
 *     <li>Blocking JNI methods: Carrier-thread are pinned while invoking a JNI method. As a result, the simulator
 *     will be blocked until the JNI method returns</li>
 * </ul>
 */
public final class Simulator {
    private enum ExitReason {
        OK("The plan has been fully executed"),
        ERROR("The plan has failed"),
        ERR_STUCK("The simulation has been interrupted because the plan was stuck and not able to make progress"),
        SIMULATION_ERROR("The simulation has failed unexpectedly");

        final String message;

        ExitReason(String message) {
            this.message = message;
        }
    }

    /**
     * Thrown when an unrecoverable error has been encountered during the simulation.
     * <p>
     * Some low-level resource might have leaks (like VirtualThread, Continuation, ...) or be in an
     * undetermined state. At this point the JVM should be considered unusable and must be exited.
     */
    public static final class SimulationError extends VirtualMachineError {
        @Serial
        private static final long serialVersionUID = 1L;

        SimulationError(String message) {
            super(message);
        }

        SimulationError(Throwable cause) {
            super(cause);
        }

        SimulationError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The source of randomness used by the simulator and everything running inside the simulator. */
    public final class SourceOfRandomness extends Random {
        @Serial
        private static final long serialVersionUID = 1L;

        private final ArrayDeque<Segment> segments;
        private long iteration;
        private long nextIteration;
        private int last;

        private SourceOfRandomness(List<Segment> segments) {
            this.segments = new ArrayDeque<>(segments);
            var segment = this.segments.removeFirst();
            setSeed(segment.seed());
            nextIteration = segment.iteration();
        }

        /** {@return the number of time a random number has been generated} */
        public long iteration() {
            return iteration;
        }

        /**
         * {@return the next random number}
         *
         * @param bits size in bits of the random number to generate
         */
        public int nextBits(int bits) {
            return next(bits);
        }

        @Override
        protected int next(int bits) {
            if (iteration == nextIteration) {
                try {
                    var segment = segments.removeFirst();
                    assert nextIteration > iteration;
                    nextIteration = segment.iteration();
                    setSeed(segment.seed());
                } catch (NoSuchElementException e) {
                    Simulator.this.exit(ExitReason.OK, new RuntimeException());
                }
            }
            iteration++;
            last = super.next(bits);
            return last;
        }
    }

    /** Thrown by {@link Runtime#exit(int)} after stopping all the machine's threads. */
    public static class SystemExitError extends Error {
        @Serial
        private static final long serialVersionUID = 1L;

        private final int exitCode;

        /**
         * Constructs a new {@link SystemExitError} instance with the specified exit code.
         *
         * @param exitCode the exitCode code provided to {@link System#exit(int)}
         */
        public SystemExitError(int exitCode) {
            this.exitCode = exitCode;
        }

        /** {@return The status code provided to System.exit()} */
        public int exitCode() {
            return exitCode;
        }
    }

    public static final Path ROOT_DIR = Path.of(getProperty("opendst.root-dir", "target"), "opendst")
            .toAbsolutePath()
            .normalize();
    // Visible for testing
    static final Set<String> REDIRECT_CONSTRUCTORS_OF =
            Set.of("java/lang/Thread", "java/net/Socket", "java/net/ServerSocket");
    // 2015-10-21, back to the future.
    private static final Instant START_TIME = Instant.ofEpochSecond(1445385600);

    private record UncaughtException(Machine machine, Thread thread, Throwable throwable) {}

    private static final ThreadLocal<Machine> MACHINE = new ThreadLocal<>();
    private final Map<InetAddress, String> addressToName = new HashMap<>();
    private final Map<String, Node> nameToNode = new HashMap<>();
    final Lock lock = new ReentrantLock();
    private final ConsoleCapture logger;
    private final Plan plan;
    private final SourceOfRandomness random;
    /** Contains the tasks waiting to be executed in the simulated environment. */
    private final PriorityQueue<QueuedTask> taskQueue = new PriorityQueue<>();
    /** The simulated wall-clock time. */
    private Instant now = START_TIME;

    final Path runBaseDir;
    /** The task id is used to break ties in the scheduling of tasks with the same launch time. */
    private long taskId;

    private Simulator(Plan plan, LogProcessor logProcessor) throws IOException {
        this.plan = plan;
        this.random = new SourceOfRandomness(plan.segments());
        this.runBaseDir = ROOT_DIR.resolve(plan.rid() + "-" + randomUUID()).normalize();
        if (exists(runBaseDir, NOFOLLOW_LINKS)) {
            throw new IOException("The directory " + runBaseDir + " already exists");
        }
        Files.createDirectories(runBaseDir);
        if (!runBaseDir.toRealPath().startsWith(ROOT_DIR)) {
            throw new IOException("Invalid plan id '" + plan.rid() + "'");
        }
        this.logger = new ConsoleCapture(plan.rid(), logProcessor, System.out);
        this.logger.log("Simulation started", Map.of("at", instant()));
    }

    void exit(ExitReason reason, Throwable cause) {
        nameToNode.values().forEach(Machine::flush);
        logger.log(
                "Terminated",
                Map.of(
                        "reason",
                        reason.message,
                        "at",
                        instant(),
                        "iteration",
                        iteration(),
                        "lastRandom",
                        random.last,
                        "cause",
                        cause != null ? cause : "<unknown>"));
        int code = ExitReason.ERROR.equals(reason) ? 1 : 0;
        try {
            logger.flush();
        } catch (Exception e) {
            e.printStackTrace(logger.getOut());
            code = 1;
        } finally {
            try {
                deleteRunBaseDir();
            } catch (IOException e) {
                e.printStackTrace(logger.getOut());
            }
            getRuntime().halt(code);
        }
    }

    public SourceOfRandomness random() {
        return random;
    }

    private void deleteRunBaseDir() throws IOException {
        if (!exists(runBaseDir) || !runBaseDir.toRealPath().startsWith(ROOT_DIR)) {
            return;
        }
        walkFileTree(runBaseDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Delete the file itself
                Files.delete(file);
                return CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                // If an exception happened inside the directory, 'exc' won't be null.
                if (exc != null) throw exc;

                // Delete the directory now that it is confirmed empty
                Files.delete(dir);
                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Handle cases where a file exists but can't be accessed
                err.println("Failed to access file: " + file + " due to " + exc.getMessage());
                return FileVisitResult.TERMINATE; // Stop everything if we hit an error
            }
        });
    }

    record Plan(String rid, String timeout, List<Segment> segments) {
        record Segment(long seed, long iteration) {}

        Plan {
            segments = List.copyOf(segments);
        }
    }

    /**
     * Runs the provided scenario into a deterministic execution environment.
     *
     * @param scenario The scenario to run in the simulated environment.
     */
    public static void runSimulation(Callable<Void> scenario) {
        runSimulation(scenario, _ -> {});
    }

    /**
     * Runs the provided scenario into a deterministic execution environment.
     *
     * @param scenario     The scenario to run in the simulated environment.
     * @param logProcessor The processor receiving the console output produced by systems running in the
     *                     simulation. This processor is executed out of the simulation context: the simulation is
     *                     paused and will not be impacted by the execution of this processor, unless the processor
     *                     throws an {@link Exception} in which case the simulation will be immediately stopped and a
     *                     failure will be reported.
     */
    public static void runSimulation(Callable<Void> scenario, LogProcessor logProcessor) {
        if (MACHINE.get() != null) {
            throw new IllegalStateException(format(
                    "The carrier thread '%s' is already running a simulation",
                    currentThread().getName()));
        } else if (!getBoolean(AGENT_PROPERTY)) {
            throw new IllegalStateException("The simulation cannot start because the simulator agent is not present");
        }

        var err = System.err;
        var plan = JSON.std.beanFrom(Plan.class, System.in);
        Segment last = null;
        for (var segment : plan.segments()) {
            if (last != null && last.iteration > segment.iteration) {
                System.err.println("The plan is invalid: at least one segment is out of order");
                getRuntime().halt(1);
            }
            last = segment;
        }

        currentThread().setName("OpenDST Simulator Carrier Thread");
        try {
            var simulator = new Simulator(plan, logProcessor);
            try {
                simulator.run(scenario);
            } catch (Throwable e) {
                simulator.logger.log(
                        "Simulation failed",
                        Map.of(
                                "exception", e,
                                "vtime", simulator.instant(),
                                "iteration", simulator.iteration()));
                simulator.exit(ExitReason.ERROR, e);
            }
        } catch (IOException e) {
            e.printStackTrace(err);
            getRuntime().halt(1);
        }
    }

    /**
     * Starts a new node in the simulation environment.
     * <p>
     * The node is identified by the given host name and IP address, and is initialized using the provided bootstrap
     * runnable. This method should be called from within a running simulation.
     *
     * @param hostName  the name of the host to start
     * @param ipAddress the IP address of the host
     * @param bootstrap the bootstrap runnable to initialize the node
     */
    public static void startNode(String hostName, String ipAddress, Callable<Void> bootstrap) throws IOException {
        new Node(machineOrThrow().simulator, getSystemClassLoader(), hostName, ipAddress).startNode(bootstrap);
    }

    /** {@return the working directory of the current node} */
    public static Path workingDirectory() {
        return machineOrThrow().workingDirectory();
    }

    Instant instant() {
        return now;
    }

    long iteration() {
        return random.iteration();
    }

    String runId() {
        return plan.rid();
    }

    /**
     * Starts a new node in the simulation environment.
     * <p>
     * This method initializes a new node with the specified host name, IP address, class loader, and main method.
     * The node is started by invoking the provided main method with the given arguments.
     * <p>
     * This method should be called from within a running simulation.
     *
     * @param hostName            the name of the host to start
     * @param ipAddress           the IP address of the host
     * @param classLoader         the class loader to use for the node
     * @param filesystemSourceDir the directory containing the initial content which will be copied into the node's
     *                            filesystem.
     * @param main                the main method to invoke for starting the node
     * @param args                the arguments to pass to the main method
     * @throws IllegalArgumentException if the node cannot be started
     * @throws IllegalStateException    if the method is not called from within a running simulation.
     */
    public static void startNode(
            String hostName,
            InetAddress ipAddress,
            ClassLoader classLoader,
            Path filesystemSourceDir,
            Method main,
            String[] args) {
        var simulator = machineOrThrow().simulator;
        try {
            var node = new Node(simulator, classLoader, hostName, ipAddress.getHostAddress());
            if (filesystemSourceDir != null) {
                initFileSystem(filesystemSourceDir, node.workingDirectory());
            }
            node.startNode(() -> {
                try {
                    main.invoke(null, (Object) args);
                    return null;
                } catch (InvocationTargetException e) {
                    if (e.getCause() != null && e.getCause() instanceof SystemExitError) {
                        return null;
                    }
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to start node " + hostName, e);
        }
    }

    @SuppressWarnings("NullableProblems")
    private static void initFileSystem(Path source, Path target) throws IOException {
        try {
            walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    createDirectories(target.resolve(source.relativize(dir)));
                    return CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    copy(file, target.resolve(source.relativize(file)));
                    return CONTINUE;
                }
            });
        } catch (NoSuchFileException e) {
            // No fs
        }
    }

    private static Machine machineOrThrow() {
        var machine = machineOrNull();
        if (machine == null) {
            throw new IllegalStateException(format(
                    "This operation cannot be performed from the thread '%s' as it is not part of a simulation",
                    currentThread().getName()));
        }
        return machine;
    }

    /**
     * Stops the node with the specified host name in the simulation environment.
     * <p>
     * This method should be called from within a running simulation. If the node exists, it will be stopped.
     *
     * @param hostName the name of the host to stop
     */
    public static void stopNode(String hostName) {
        machineOrThrow().simulator.stopNode0(hostName);
    }

    private void stopNode0(String hostName) {
        var node = nameToNode.get(hostName.toLowerCase());
        if (node != null) {
            node.stopNode();
        }
    }

    private void run(Callable<Void> scenario) throws Exception {
        var bootstrap = new Node(this, getSystemClassLoader(), "simulator", "127.0.0.1");
        MACHINE.set(bootstrap);
        bootstrap.startNode(scenario);
        for (var task = taskQueue.poll();
                task != null /* && wallClockNanoTime() < deadline */;
                task = taskQueue.poll()) {
            if (task.runAt.isBefore(now)) {
                exit(ExitReason.SIMULATION_ERROR, new SimulationError("Simulator has gone backward in time"));
            } else if (task.skip) {
                continue;
            }
            now = task.runAt;
            currentThread().setContextClassLoader(task.machine.classLoader);
            System.setOut(task.machine.console);
            System.setErr(task.machine.console);

            MACHINE.set(task.machine);
            try {
                task.run();
            } finally {
                MACHINE.remove();
            }
            if (!SUCCESS.equals(task.state())) {
                exit(ExitReason.SIMULATION_ERROR, new SimulationError("Task failed", task.exceptionNow()));
            }
            try {
                logger.processLogs();
            } catch (Exception e) {
                exit(ExitReason.ERROR, e);
            }
            if (task.taskId % 100 == 0) {
                nameToNode.values().forEach(Machine::flush);
                logger.flush();
            }

            // To keep determinism, unblock all the simulator-carried virtual threads that have exited an object monitor
            // during the previous task execution before the next iteration.
            task.machine.purgeAndUnblockVirtualThreads();
            var machine = task.machine;
            // TODO: this check is great a detecting sources of non-determinism but it is slow. It should probably be
            //  conditioned (e.g. doing it once every hundred runs)
            nameToNode.values().forEach(node -> node.checkNoThreadOnWaitingList(machine));
        }
        nameToNode.values().forEach(Machine::flush);

        if (allThreadsAreStuck()) {
            exit(ExitReason.ERR_STUCK, null);
        }
    }

    private boolean allThreadsAreStuck() {
        for (var node : nameToNode.values()) {
            for (var thread : node.virtualThreads) {
                if (thread.isAlive()) {
                    err.println("Warning: simulation ended prematurely because all remaining threads are blocked");
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The default uncaught exception handler used by every simulator-carried virtual thread created by this simulator.
     * When invoked it'll log a message which is expected to be caught by the orchestrator.
     *
     * @param machine   The machine where the exception has been thrown
     * @param thread    The simulator-carried virtual thread which exited because of the uncaught exception
     * @param throwable The uncaught exception
     */
    void uncaughtExceptionHandler(Machine machine, Thread thread, Throwable throwable) {
        logger.log(
                "Uncaught exception",
                Map.of(
                        "vhost",
                        machine.hostName,
                        "thread",
                        thread.getName(),
                        "iteration",
                        machine.simulator.iteration(),
                        "vtime",
                        machine.simulator.instant(),
                        "exception",
                        throwable));
    }

    private Future<?> scheduleExactlyAt(Machine machine, Runnable task, Instant at) {
        if (machineOrNull() == null) {
            // We're still executing the task as this case also happens when we dump the stacktrace of all the
            // virtual threads. The deterministic behavior is no more guaranteed from here.
            logger.log(
                    "The scheduler has been invoked from a non-simulated thread, determinism might be lost",
                    Map.of("thread", currentThread().getName()));
            task.run();
            return new FutureTask<>(() -> {}, null);
        } else if (at.isBefore(now)) {
            // This should never happen, famous words.
            exit(ExitReason.SIMULATION_ERROR, new SimulationError("Cannot schedule a task in the past"));
        }
        var queuedTask = new QueuedTask(machine, at, task, taskId++);
        taskQueue.add(queuedTask);
        return queuedTask;
    }

    /**
     * Keeps track of an error when this one cannot be thrown immediately because we're invoked by the JDK possibly
     * from an internal thread (e.g: unparker, unblocker, ...). The error will then be thrown at the next run-loop
     * iteration.
     */
    private void deferException(Machine machine, Throwable t) {
        uncaughtExceptionHandler(machine, currentThread(), t);
    }

    /** {@return The Simulation attached to this thread or null if this thread is not part of a simulation}. */
    public static Machine machineOrNull() {
        var machine = MACHINE.get();
        if (machine == null) {
            MACHINE.remove();
        }
        return machine;
    }

    private final class QueuedTask extends FutureTask<Void> implements Comparable<QueuedTask> {
        private static final Comparator<QueuedTask> COMPARATOR =
                Comparator.<QueuedTask, Instant>comparing(q -> q.runAt).thenComparingLong(a -> a.taskId);
        private final Machine machine;
        private final Instant runAt;
        private final long taskId;
        private boolean skip;

        private QueuedTask(Machine machine, Instant runAt, Runnable task, long taskId) {
            super(task, null);
            this.machine = machine;
            this.runAt = runAt;
            this.taskId = taskId;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                // No need to execute it
                skip = true;
            }
            return cancelled;
        }

        @Override
        public int compareTo(QueuedTask other) {
            return COMPARATOR.compare(this, other);
        }
    }

    /** Deterministic implementation of {@link Socket#Socket()}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket() throws IOException {
        return socketFactory().createSocket();
    }

    private static SocketFactory socketFactory() {
        var machine = machineOrNull();
        return machine != null ? machine.socketFactory() : SocketFactory.getDefault();
    }

    /** Deterministic implementation of {@link Socket#Socket(InetAddress, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(InetAddress address, int port) throws IOException {
        return socketFactory().createSocket(address, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, boolean)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused", "removal"})
    public static Socket newSocket(InetAddress address, int port, boolean stream) throws IOException {
        if (stream) {
            throw new UnsupportedOperationException("Cannot create a stream socket");
        }
        return newSocket(address, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, InetAddress, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return socketFactory().createSocket(address, port, localAddress, localPort);
    }

    /** Deterministic implementation of {@link Socket#Socket(Proxy)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(Proxy proxy) {
        var simulator = machineOrNull();
        if (simulator != null) {
            throw new UnsupportedOperationException("Cannot create a proxy socket");
        }
        return new Socket(proxy);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(String host, int port) throws IOException {
        return socketFactory().createSocket(host, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, boolean)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused", "removal"})
    public static Socket newSocket(String host, int port, boolean stream) throws IOException {
        if (stream) {
            throw new UnsupportedOperationException("Cannot create a stream socket");
        }
        return socketFactory().createSocket(host, port);
    }

    /** Deterministic implementation of {@link Socket#Socket(String, int, InetAddress, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Socket newSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
        return socketFactory().createSocket(host, port, localAddress, localPort);
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket()}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket() throws IOException {
        return serverSocketFactory().createServerSocket();
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket(int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket(int port) throws IOException {
        return serverSocketFactory().createServerSocket(port);
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket(int, int)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket(int port, int backlog) throws IOException {
        return serverSocketFactory().createServerSocket(port, backlog);
    }

    /** Deterministic implementation of {@link ServerSocket#ServerSocket(int, int, InetAddress)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static ServerSocket newServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
        return serverSocketFactory().createServerSocket(port, backlog, bindAddr);
    }

    private static ServerSocketFactory serverSocketFactory() {
        var machine = machineOrNull();
        return machine != null ? machine.serverSocketFactory() : ServerSocketFactory.getDefault();
    }

    /** Deterministic implementation of {@link Executors#defaultThreadFactory()}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public ThreadFactory executorsDefaultThreadFactory() {
        return ofVirtual().factory();
    }

    /** Deterministic implementation of {@link Thread#Thread()}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod"})
    public static Thread newThread() {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().unstarted(() -> {}) : new Thread();
    }

    /** Deterministic implementation of {@link Thread#Thread(String)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod"})
    public static Thread newThread(String name) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().name(name).unstarted(() -> {}) : new Thread(name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused", "InstantiatingAThreadWithDefaultRunMethod"})
    public static Thread newThread(ThreadGroup group, String name) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().name(name).unstarted(() -> {}) : new Thread(group, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(Runnable)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(Runnable target) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().unstarted(target) : new Thread(target);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(ThreadGroup group, Runnable target) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().unstarted(target) : new Thread(group, target);
    }

    /** Deterministic implementation of {@link Thread#Thread(Runnable, String)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(Runnable target, String name) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().name(name).unstarted(target) : new Thread(target, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(ThreadGroup group, Runnable target, String name) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().name(name).unstarted(target) : new Thread(group, target, name);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(ThreadGroup group, Runnable target, String name, long stackSize) {
        var machine = machineOrNull();
        return machine != null ? ofVirtual().name(name).unstarted(target) : new Thread(group, target, name, stackSize);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, Runnable, String, long, boolean)}. */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(
            ThreadGroup group, Runnable target, String name, long stackSize, boolean inheritThreadLocal) {
        var machine = machineOrNull();
        return machine != null
                ? ofVirtual().name(name).unstarted(target)
                : new Thread(group, target, name, stackSize, inheritThreadLocal);
    }

    /** Deterministic implementation of {@link Thread#Thread(ThreadGroup, String, int, Runnable, long)} . */
    @SuppressWarnings({"checkstyle:JavadocMethod", "unused"})
    public static Thread newThread(
            ThreadGroup group, String name, int characteristics, Runnable target, long stackSize) {
        var machine = machineOrNull();
        return machine != null
                ? ofVirtual().name(name).unstarted(target)
                : VirtualThreadInternals.newThread(group, name, characteristics, target, stackSize);
    }

    /**
     * Replaces {@code VirtualThread#unblockVirtualThreads()} which processes virtual threads exiting object monitor.
     * <p>
     * As a result of JEP-491, object monitors are no more a cause of thread pinning. When a virtual thread exits an
     * object monitor, it is added to an internal linked-list: the waiting list. These virtual threads are waiting for
     * being re-scheduled. All this is happening at the C++ level. The linked-list is exposed on the Java side by
     * {@code VirtualThread#takeVirtualThreadListToUnblock()} which is processed by dedicated platform thread:
     * "VirtualThread-unblocker". This method blocks until at least one virtual thread has been added to the
     * linked-list. The VirtualThread-unblocker thread will then invoke {@code VirtualThread#unblock()} on each
     * virtual thread present in this list. As a result, all the virtual threads in the waiting list are rescheduled.
     * <p>
     * The VirtualThread-unblocker is a platform thread which does not expose any guarantee regarding the processing of
     * the list. It is not possible to know deterministically when a virtual thread present in the list will effectively
     * be rescheduled.
     * <p>
     * This {@link VirtualThreadUnblocker#unblockVirtualThreads()} is a reimplementation of the original
     * {@code VirtualThread#unblockVirtualThreads()}. It still runs on the same "VirtualThread-unblocker" and performs
     * the same tasks as the original method but, to not break the determinism, it will only unblock platform-carried
     * virtual threads. The simulator-carried virtual threads are present on this waiting-list will be ignored by
     * this method and will be processed after each task invocation in the {@link #start()}.
     */
    public static final class VirtualThreadUnblocker {
        /**
         * Unblocks all the virtual threads present on the waiting list.
         * <p>
         * This method blocks until at least one virtual thread has been added to the waiting list. It will then
         * process all the platform-carried virtual threads present in the waiting list. The simulator-carried virtual
         * threads are ignored by this method and are unblocked in the {@link #start()} to preserve determinism.
         */
        public static void unblockVirtualThreads() {
            for (; ; ) {
                var vthread = takeVirtualThreadListToUnblock();
                while (vthread != null) {
                    var nextThread = getNext(vthread);
                    clearNext(vthread);
                    var machineOrNull = getThreadLocal(vthread, MACHINE);
                    if (machineOrNull == null) {
                        boolean changed = compareAndSetOnWaitingList(vthread, true, false);
                        assert changed;
                        unblock(vthread);
                    }
                    vthread = nextThread;
                }
            }
        }

        private VirtualThreadUnblocker() {
            // Prevent instantiation
        }
    }

    boolean registerDns(String hostName, Node host) {
        var lowerCaseHostName = hostName.toLowerCase(ROOT);
        if (nameToNode.putIfAbsent(lowerCaseHostName, host) == null) {
            host.inetAddresses().forEach(address -> {
                if (!address.isLoopbackAddress()) {
                    addressToName.put(address, lowerCaseHostName);
                }
            });
            return true;
        }
        return false;
    }

    void unregisterDns(String hostName) {
        var hostname = hostName.toLowerCase(ROOT);
        var host = nameToNode.remove(hostname);
        if (host != null) {
            host.inetAddresses().forEach(address -> addressToName.remove(address, hostname));
        }
    }

    String lookupByAddress(InetAddress addr) throws UnknownHostException {
        var hostName = addressToName.get(addr);
        if (hostName == null) {
            throw new UnknownHostException(addr.getHostAddress());
        }
        return hostName;
    }

    private Node lookupHostByAddress(InetAddress addr) throws UnknownHostException {
        var host = nameToNode.get(lookupByAddress(addr));
        if (host == null) {
            throw new UnknownHostException(addr.getHostAddress());
        }
        return host;
    }

    Stream<InetAddress> lookupByName(String name) throws UnknownHostException {
        var resolvedHost = nameToNode.get(name.toLowerCase());
        if (resolvedHost == null) {
            throw new UnknownHostException(name);
        }
        return resolvedHost.inetAddresses();
    }

    SocketImpl route(InetAddress from, InetAddress addr, int port) throws UnknownHostException, NoRouteToHostException {
        if (from.isLoopbackAddress() && !addr.isLoopbackAddress()) {
            throw new NoRouteToHostException();
        }
        return lookupHostByAddress(addr).route(from, addr, port);
    }

    /** Provides deterministic implementation of JDK API. */
    public abstract static class Machine {
        public static final Duration SHUTDOWN_GRACE_TIME = ofSeconds(30);
        public final Simulator simulator;
        private final Path workingDirectory;
        private final ClassLoader classLoader;
        final List<Thread> virtualThreads = new ArrayList<>();
        private final PrintStream console;
        private final long salt32l;
        private final boolean reverse;
        private final ToLongFunction<Random> defaultSchedulingJitter = r -> r.nextLong(1, 10_000);
        final String hostName;
        private final List<Thread> shutdownHooks = new ArrayList<>();
        private final CompletableFuture<Integer> shutdown = new CompletableFuture<>();
        private boolean stopped;

        Machine(Simulator simulator, ClassLoader classLoader, String hostName) throws IOException {
            this.simulator = simulator;
            this.classLoader = classLoader;
            this.workingDirectory = simulator.runBaseDir.resolve("fs").resolve(hostName);
            createDirectories(workingDirectory);
            this.hostName = hostName.toLowerCase();
            this.salt32l = simulator.random.nextLong() & 0xFFFF_FFFFL;
            this.reverse = (salt32l & 1) == 0;
            this.console = new PrintStream(simulator.logger.newLogWriter(simulator, hostName), true);
        }

        PrintStream console() {
            return console;
        }

        void flush() {
            console.flush();
        }

        /** Deterministic implementation of {@link InetAddress#getLocalHost()}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract InetAddress getLocalHost();

        public Instant instant() {
            return simulator.instant();
        }

        /** Deterministic implementation of {@link InetAddressResolver#lookupByAddress(byte[])}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract String lookupByAddress(InetAddress byAddress) throws UnknownHostException;

        /** Deterministic implementation of {@link InetAddressResolver#lookupByName(String, LookupPolicy)}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract Stream<InetAddress> lookupByName(String host) throws UnknownHostException;

        /** Deterministic implementation of {@link SocketFactory}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract SocketFactory socketFactory();

        /** Deterministic implementation of {@link ServerSocketFactory}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public abstract ServerSocketFactory serverSocketFactory();

        /**
         * Attaches the provided thread to this machine. Once attached, invocation of {@link #machineOrNull()} from that
         * thread will return this machine instance.
         *
         * @param thread A newly created thread
         */
        public void attachThread(Thread thread) {
            assert thread.isVirtual();
            virtualThreads.add(thread);
            setThreadLocal(thread, MACHINE, this);
            thread.setUncaughtExceptionHandler(this::uncaughtExceptionHandler);
            thread.setContextClassLoader(classLoader);
        }

        void startNode(Callable<Void> bootstrap) {
            scheduleNow(
                    () -> ofVirtual().name(format("start-node-%s", hostName)).start(() -> {
                        try {
                            bootstrap.call();
                        } catch (Throwable e) {
                            uncaughtExceptionHandler(currentThread(), e);
                        }
                    }));
        }

        /** Stops this node. Behaves as if the node had invoked {@link Runtime#exit}. */
        void stopNode() {
            if (!stopped) {
                // Scheduling a new thread while this node is already being stopped would result in infinite loop as
                // the termination of this thread might lead purgeAndUnblockVirtualThreads() to invoke this stopNode()
                // method again.
                scheduleNow(
                        () -> ofVirtual().name(format("stop-node-%s", hostName)).start(() -> exit(0)));
            }
        }

        /**
         * Overrides {@link Runtime#exit(int)}.
         *
         * @param status exit status.
         */
        public void exit(int status) {
            if (stopped) {
                return;
            }
            stopped = true;
            shutdownHooks.forEach(Thread::start);
            try {
                if (!virtualThreads.remove(currentThread())) {
                    simulator.exit(
                            ExitReason.SIMULATION_ERROR,
                            new SimulationError(format(
                                    "exit() has been invoked from thread '%s' which is not part of the node '%s'",
                                    currentThread().getName(), hostName)));
                }
                if (waitUntilAllThreadExits()) {
                    // End of the grace period, interrupt all remaining threads
                    virtualThreads.forEach(Thread::interrupt);
                    waitUntilAllThreadExits();
                }
                for (var thread : virtualThreads) {
                    if (thread.isAlive()) {
                        var stack = new IllegalStateException("Stack trace");
                        stack.setStackTrace(thread.getStackTrace());
                        simulator.exit(
                                ExitReason.SIMULATION_ERROR,
                                new SimulationError(
                                        format(
                                                "The thread '%s' from node '%s' cannot be stopped.",
                                                thread.getName(), hostName),
                                        stack));
                    }
                }
                shutdown.complete(status);
            } catch (InterruptedException e) {
                throw new SimulationError(e);
            } finally {
                simulator.unregisterDns(hostName);
            }
        }

        private boolean waitUntilAllThreadExits() throws InterruptedException {
            long deadlineMs = currentTimeMillis() + SHUTDOWN_GRACE_TIME.toMillis();
            boolean someThreadStillAlive;
            do {
                someThreadStillAlive = false;
                for (var thread : new ArrayList<>(virtualThreads)) {
                    if (thread.isAlive() && !thread.join(ofSeconds(1))) {
                        someThreadStillAlive = true;
                    }
                }
            } while (someThreadStillAlive && currentTimeMillis() < deadlineMs);
            return someThreadStillAlive;
        }

        /**
         * Iterates over all the simulator-carried virtual threads, removing the terminated threads and unblocking
         * the threads which exited a monitor. This is costly and a better alternative would be to leverage
         * {@code ThreadContainers#onExit()} and an implementation of
         * {@link VirtualThread#takeVirtualThreadListToUnblock()} which would return immediately if the list is empty
         * rather than blocking. Unfortunately this implies modifying the JDK code.
         */
        void purgeAndUnblockVirtualThreads() {
            for (var it = virtualThreads.iterator(); it.hasNext(); ) {
                var thread = it.next();
                if (TERMINATED.equals(thread.getState())) {
                    it.remove();
                } else if (isOnWaitingList(thread)) {
                    // Wait until this thread has been detached from the waiting list by the unblocked-thread
                    while (getNext(thread) != null) {
                        onSpinWait();
                    }
                    if (!compareAndSetOnWaitingList(thread, true, false)) {
                        // Should never happen. One possible explanation is that this thread has been unblocked by the
                        // VirtualThread-unblocker. Proof that this run is no more deterministic.
                        throw new SimulationError(format(
                                "Thread '%s' is no more present on the waiting-list. Determinism is broken",
                                thread.getName()));
                    }
                    unblock(thread);
                }
            }
        }

        void checkNoThreadOnWaitingList(Machine machine) {
            if (machine != this) {
                for (var thread : virtualThreads) {
                    if (isOnWaitingList(thread)) {
                        var ise = new IllegalStateException("Stack trace");
                        ise.setStackTrace(thread.getStackTrace());
                        throw new SimulationError(
                                format(
                                        "Thread '%s' from machine '%s' is unexpectedly present on the waiting-list",
                                        thread.getName(), hostName),
                                ise);
                    }
                }
            }
        }

        /**
         * Executes the provided runnable in the context of this simulator.
         * <p>
         * The runnable will be scheduled with a small jitter to simulate the non-deterministic scheduling of the
         * virtual threads.
         *
         * @param runnable The runnable to execute in the context of this simulator
         */
        public void scheduleNow(Runnable runnable) {
            simulator.scheduleExactlyAt(
                    this, runnable, simulator.now.plusNanos(defaultSchedulingJitter.applyAsLong(simulator.random)));
        }

        /**
         * Schedules the provided runnable to be executed at the specified time in the future.
         * <p>
         * The runnable will be scheduled with a small jitter to simulate the non-deterministic scheduling of the
         * virtual threads.
         *
         * @param runnable The runnable to execute in the context of this simulator
         * @param delay    The delay after which the runnable should be executed
         * @param unit     The time unit of the delay
         * @return A {@link Future} representing pending completion of the task
         */
        public Future<?> scheduleAfterDelay(Runnable runnable, long delay, TimeUnit unit) {
            return simulator.scheduleExactlyAt(this, runnable, simulator.now.plus(delay, unit.toChronoUnit()));
        }

        /** Deterministic implementation of {@code java.util.ImmutableCollections#REVERSE}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public final boolean immutableCollectionsReverse() {
            return reverse;
        }

        /** Deterministic implementation of {@code java.util.ImmutableCollections#SALT32L}. */
        public final long immutableCollectionsSalt32l() {
            return salt32l;
        }

        /** {@return the current simulated wall-clock time in nanoseconds.} */
        public long nanoTime() {
            return NANOS.between(START_TIME, simulator.now);
        }

        /** {@return the current simulated wall-clock time in milliseconds.} */
        public long currentTimeMillis() {
            return simulator.now.toEpochMilli();
        }

        /** Deterministic implementation of {@link Runtime#addShutdownHook(Thread)}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public void addShutdownHook(Thread hook) {
            shutdownHooks.add(hook);
        }

        /** Deterministic implementation of {@link Runtime#removeShutdownHook(Thread)}. */
        @SuppressWarnings("checkstyle:JavadocMethod")
        public boolean removeShutdownHook(Thread hook) {
            return shutdownHooks.remove(hook);
        }

        /**
         * Registers an uncaught exception for the specified thread in the context of this simulator.
         * <p>
         * This method is called when a thread terminates due to an uncaught exception. The exception is stored
         * and will be thrown as a {@link Simulator.SimulationError} at the end of the current run-loop.
         *
         * @param thread    the thread that encountered the uncaught exception
         * @param throwable the uncaught exception
         */
        public void uncaughtExceptionHandler(Thread thread, Throwable throwable) {
            simulator.uncaughtExceptionHandler(this, thread, throwable);
        }

        /** {@return the working directory of the current node} */
        public Path workingDirectory() {
            return workingDirectory;
        }
    }
}
