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

import static java.lang.Double.compare;
import static java.lang.Long.parseLong;
import static java.lang.Math.abs;
import static java.lang.Math.log;
import static java.lang.Math.max;
import static java.lang.Math.round;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.time.Duration.ofSeconds;
import static java.util.Comparator.comparing;
import static java.util.Objects.hash;
import static java.util.concurrent.ThreadLocalRandom.current;
import static net.openhft.hashing.LongHashFunction.xx3;

import com.pingidentity.opendst.maven.Orchestrator.Plan.Segment;
import it.unimi.dsi.fastutil.longs.AbstractLong2ObjectMap.BasicEntry;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.jr.ob.JSON;

/**
 * Chose between:
 * - Creating a plan from a brand new timeline
 * - Creating a plan from an existing timeline, simply running it longer
 * - Creating a plan by branching an existing timeline at some interesting moment
 */
final class AcoOrchestrator implements Orchestrator {
    public static final int NGRAM_DEPTH = 1;
    private static final int PRIME = 31;

    private static final long PN =
            BigInteger.valueOf(PRIME).pow(NGRAM_DEPTH - 1).longValue();

    private static final double PHEROMONES_LOW_LIMIT = 0.1;
    private static final double PHEROMONES_EVAPORATION = 0.1;
    private static final int SAVED_FAILURE_PLAN_MAX = 20;
    private static final String SAVED_FAILURE_PLAN_DIR = "target/opendst/failures";

    private static final class NGram {
        private final String[] signals;
        private final int capacity;
        private final long[] window;
        private int position;
        private long currentHash;
        private int count;

        NGram(int capacity) {
            this.capacity = capacity;
            this.window = new long[capacity];
            this.signals = new String[capacity];
        }

        long add(String signal) {
            long signalHash = xx3().hashChars(signal);
            if (count < capacity) {
                currentHash = currentHash * PRIME + signalHash;
                signals[count] = signal;
                window[count++] = signalHash;
                return currentHash;
            }
            // Slide the window: Remove oldest, Add newest
            long oldest = window[position];
            currentHash = (currentHash - oldest * PN) * PRIME + signalHash;
            window[position] = signalHash;
            signals[position] = signal;
            position = (position + 1) % capacity;
            return currentHash;
        }

        public void reset() {
            currentHash = position = count = 0;
        }
    }

    record LogStatement(String rid, long it, String source, LogMessage log) {
        boolean isValid() {
            return rid != null && source != null && log != null && log.message != null;
        }

        boolean isStartOfSimulation() {
            return "simulator".equals(source) && "Terminated".equalsIgnoreCase(log.message());
        }

        boolean isEndOfSimulation() {
            return "simulator".equals(source) && "Terminated".equalsIgnoreCase(log.message());
        }
    }

    private record LogMessage(int code, int last, String message) {}

    private static final class Timeline {
        public static final Timeline ROOT = new Timeline(0, new Branch(null, 0, 0));

        private final long id;
        private final NGram signalSequence;
        private Branch branch;
        private String lastLogLine;

        Timeline(long id, Branch branch) {
            this.id = id;
            this.branch = branch;
            this.signalSequence = new NGram(NGRAM_DEPTH);
        }

        public void reset() {
            signalSequence.reset();
        }

        long id() {
            return id;
        }

        String lastLogLineOrNull() {
            return lastLogLine;
        }

        long addSignal(String signal) {
            return signalSequence.add(signal);
        }

        void setLastLogLine(String lastLogLine) {
            this.lastLogLine = lastLogLine;
        }

        Timeline runLonger(long increment) {
            branch = branch.extendBy(increment);
            lastLogLine = null;
            return this;
        }

        Moment getMomentAt(long iteration) {
            // TODO: We first need to find the branch
            for (var tl = this; tl != Timeline.ROOT; tl = tl.branch.moment.timeline) {
                if (tl.branch.moment().iteration < iteration) {
                    return Moment.from(tl, iteration);
                }
            }
            throw new IllegalArgumentException("Moment not found");
        }

        Plan plan() {
            var segments = new ArrayList<Segment>();
            segments.add(new Segment(branch.seed, branch.lastIteration));
            long lastITeration = branch.lastIteration;
            for (var current = branch.moment; current != null; current = current.timeline().branch.moment) {
                segments.add(current.segment());
            }
            return new Plan(Long.toString(id), ofSeconds(30).toString(), List.copyOf(segments.reversed()));
        }
    }

    private static final class Moment implements Comparable<Moment> {
        private final Timeline timeline;
        private final long iteration;
        private double pheromones;

        static Moment from(Timeline timeline, long iteration) {
            if (iteration <= 0) {
                throw new IllegalArgumentException("iteration must be positive");
            }
            return new Moment(timeline, iteration);
        }

        private Moment(Timeline timeline, long iteration) {
            if (timeline != Timeline.ROOT && timeline.branch.moment.iteration > iteration) {
                throw new IllegalArgumentException("iteration must be greater than branch iteration");
            }
            this.timeline = timeline;
            this.iteration = iteration;
        }

        Segment segment() {
            return new Segment(timeline.branch.seed, iteration);
        }

        long iteration() {
            return iteration;
        }

        Branch branch(long dynamicDuration) {
            return new Branch(this, current().nextLong(), iteration + dynamicDuration);
        }

        double pheromones() {
            return pheromones;
        }

        void incrementPheromones(double increment) {
            pheromones += increment;
        }

        void evaporatePheromones() {
            pheromones *= (1 - PHEROMONES_EVAPORATION);
        }

        @Override
        public int hashCode() {
            return hash(timeline.id(), iteration);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Moment m
                    && Objects.equals(timeline.id(), m.timeline.id())
                    && iteration == m.iteration;
        }

        @Override
        public int compareTo(Moment o) {
            return compare(pheromones, o.pheromones);
        }

        Timeline timeline() {
            return timeline;
        }
    }

    private record Branch(Moment moment, long seed, long lastIteration) {
        Branch extendBy(long increment) {
            return new Branch(moment, seed, lastIteration + increment);
        }
    }

    /** Track the moment at witch signals have been received */
    private final File baseDir;

    private final List<Pattern> compiledPatterns = new ArrayList<>();
    private final List<Pattern> compiledFailurePatterns = new ArrayList<>();
    private final BloomFilter boringSequences = new BloomFilter(1_000_000, 0.01);
    private final Long2ObjectOpenHashMap<Moment> interestingSequences = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Timeline> runningTimelines = new Long2ObjectOpenHashMap<>();
    private final Random random = new Random();

    private static final long BASE_ITERATIONS = 100;
    private static final long MAX_ITERATIONS = 5000;
    private long timelineCount;
    private long consecutiveNonDiscoveries;
    private long runSinceLastPrune;

    AcoOrchestrator(File baseDir, Collection<String> signalLogPatterns, Collection<String> failureLogPatterns) {
        this.baseDir = baseDir;
        if (signalLogPatterns != null) {
            signalLogPatterns.stream().map(Pattern::compile).forEach(compiledPatterns::add);
        }
        if (failureLogPatterns != null) {
            failureLogPatterns.stream().map(Pattern::compile).forEach(compiledFailurePatterns::add);
        }
    }

    public synchronized Plan nextPlan() {
        new HashSet<>(interestingSequences.values()).forEach(Moment::evaporatePheromones);
        var timeline = selectOrCreateTimeline();
        timeline.reset();
        runningTimelines.put(timeline.id(), timeline);
        return timeline.plan();
    }

    private Timeline selectOrCreateTimeline() {
        long dynamicDuration = Math.min(MAX_ITERATIONS, BASE_ITERATIONS + (consecutiveNonDiscoveries * 10));
        var interestingMoment = selectInterestingMomentOrNull();
        int selector = random.nextInt(100);
        if (interestingMoment != null && selector < 60) {
            return new Timeline(++timelineCount, interestingMoment.branch(dynamicDuration));
        } else if (interestingMoment != null
                && selector < 80
                && runningTimelines.containsKey(interestingMoment.timeline().id())) {
            timelineCount++;
            return interestingMoment.timeline().runLonger(dynamicDuration);
        } else if (selector < 99 || interestingMoment == null) {
            var tl = new Timeline(
                    ++timelineCount, new Branch(new Moment(Timeline.ROOT, 0), random.nextLong(), dynamicDuration));
            double max = interestingSequences.values().stream()
                            .mapToDouble(Moment::pheromones)
                            .max()
                            .orElse(100)
                    / 2;
            tl.branch.moment().incrementPheromones(max);
            return tl;
        } else {
            // Replay 1% of the timeline without modification to validate determinism
            return interestingMoment.timeline();
        }
    }

    public synchronized void onLogReceived(Plan plan, String logLine) {
        var log = parseLogOrNull(logLine);
        if (log == null || !log.isValid()) {
            // TODO: Silent repetitive logs (e.g. JVM warnings). They're producing lot of noise
            // err.printf("Ignored: %s%n", logLine);
            return;
        }
        // TODO: Remove this hashmap lookup from hot path
        var timeline = runningTimelines.get(Long.parseLong(plan.rid()));
        if (timeline == null) {
            err.printf("BUG: No timeline found for plan: %s%n", JSON.std.asString(plan));
            exit(1);
        } else if (log.isEndOfSimulation() && !isDeterministic(timeline, plan, logLine)) {
            exit(1);
        } else if (failurePatternDetected(plan, logLine, log)) {
            if (!isDeterministic(timeline, plan, logLine)) {
                exit(1);
            }
            // TODO: Add option to continue on failure ?
            // exit(1);
        } else {
            detectSignal(timeline, log.it(), logLine);
        }
    }

    private static LogStatement parseLogOrNull(String line) {
        try {
            return JSON.std.beanFrom(LogStatement.class, line);
        } catch (JacksonException e) {
            return null;
        }
    }

    private boolean isDeterministic(Timeline timeline, Plan plan, String line) {
        var previousRun = timeline.lastLogLineOrNull();
        if (previousRun == null) {
            timeline.setLastLogLine(line);
            return true;
        }
        if (JSON.std.anyFrom(previousRun).equals(JSON.std.anyFrom(line))) {
            out.printf("Determinism verified for timeline %d%n", timeline.id());
            return true;
        } else {
            var pathOrNull = savePlanOrNull(plan);
            if (pathOrNull != null) {
                err.printf(
                        "A non-deterministic execution has been detected. "
                                + "Plan saved to '%s' (expecting=%s, current=%s%n)",
                        pathOrNull, previousRun, line);
            } else {
                err.printf(
                        "A non-deterministic execution has been detected (expecting=%s, current=%s%n). Plan=%s%n",
                        previousRun, line, JSON.std.asString(plan));
            }
            return false;
        }
    }

    private boolean failurePatternDetected(Plan plan, String logLine, LogStatement log) {
        for (var pattern : compiledFailurePatterns) {
            if (pattern.matcher(log.log.message).find()) {
                var pathOrNull = savePlanOrNull(plan);
                if (pathOrNull != null) {
                    err.printf(
                            "\u001b[31m The failure pattern '%s' has been detected !%n\tat trial %d in '%s'%n\t"
                                    + "Plan "
                                    + "saved"
                                    + " to '%s'\u001b[0m %n",
                            pattern, timelineCount, logLine, pathOrNull);
                } else {
                    err.printf(
                            "The failure pattern '%s' has been detected at trial %d in '%s'. Plan '%s'%n",
                            pattern, timelineCount, logLine, JSON.std.asString(plan));
                }
                return true;
            }
        }
        return false;
    }

    private void detectSignal(Timeline timeline, long iteration, String log) {
        for (var pattern : compiledPatterns) {
            var matcher = pattern.matcher(log);
            if (matcher.find()) {
                String found = log.substring(matcher.start(), matcher.end());
                long sequence = timeline.addSignal(found);
                if (boringSequences.mightContain(sequence)) {
                    interestingSequences.remove(sequence);
                    continue;
                }
                var bestKnownMoment = interestingSequences.get(sequence);
                if (bestKnownMoment == null) {
                    var moment = timeline.getMomentAt(iteration);
                    double max = interestingSequences.values().stream()
                            .mapToDouble(Moment::pheromones)
                            .max()
                            .orElse(100);
                    moment.incrementPheromones(max * (1.4 + (max(10, consecutiveNonDiscoveries) / 10.0)));
                    consecutiveNonDiscoveries = 0;
                    var bah = boringSequences.mightContain(sequence);
                    interestingSequences.put(sequence, moment);
                    System.out.printf(
                            "Timeline %d found a new interesting signal at %d: [ %s ]%n",
                            moment.timeline.id, iteration, Arrays.toString(moment.timeline.signalSequence.signals));
                } else if (iteration < bestKnownMoment.iteration()) {
                    consecutiveNonDiscoveries = 0;
                    var moment = timeline.getMomentAt(iteration);
                    moment.incrementPheromones(
                            bestKnownMoment.pheromones() * (1.4 + (max(10, consecutiveNonDiscoveries) / 10.0)));
                    var bah = boringSequences.mightContain(sequence);
                    interestingSequences.put(sequence, moment);
                    System.out.printf(
                            "Timeline %d discovered a known signal sooner at %d (was %d) [ %s ]%n",
                            moment.timeline.id,
                            iteration,
                            bestKnownMoment.iteration(),
                            Arrays.toString(moment.timeline.signalSequence.signals));
                }
            }
        }
    }

    public synchronized void onPlanTerminated(Plan plan, int code) {
        var timelineOrNull = runningTimelines.remove(parseLong(plan.rid()));
        if (timelineOrNull == null) {
            err.printf("BUG: A plan has terminated with code '%d' but it has no running timeline associated%n", code);
            exit(1);
        }

        consecutiveNonDiscoveries++;
        runSinceLastPrune++;
        if (runSinceLastPrune >= 500) {
            runSinceLastPrune = 0;
            if (interestingSequences.size() >= 100_000) {
                // Remove the 10k most boring sequences
                var boring = new TreeSet<Long2ObjectMap.Entry<Moment>>(
                        comparing(e -> e.getValue().pheromones()));
                interestingSequences.forEach((sequence, moment) -> {
                    boring.add(new BasicEntry<>(sequence, moment));
                    if (boring.size() >= 10_000) {
                        boring.removeLast();
                    }
                });
                boring.forEach(e -> {
                    long sequence = e.getLongKey();
                    interestingSequences.remove(sequence);
                    boringSequences.add(sequence);
                });
            }
        }

        if (code != 0) {
            var pathOrNull = savePlanOrNull(plan);
            if (code == 1) {
                if (pathOrNull != null) {
                    err.printf(
                            "The simulator has found a problem with the plan saved to '%s': %s%n",
                            pathOrNull, timelineOrNull.lastLogLineOrNull());
                } else {
                    err.printf("The simulator has found a problem with the plan '%s'%n", JSON.std.asString(plan));
                }
            } else {
                if (pathOrNull != null) {
                    err.printf("The simulation has failed unexpectedly with the plan saved to '%s'%n", pathOrNull);
                } else {
                    err.printf("The simulation has failed unexpectedly with the plan '%s'%n", JSON.std.asString(plan));
                }
            }
        }
    }

    private String savePlanOrNull(Plan plan) {
        var dir = new File(baseDir, SAVED_FAILURE_PLAN_DIR);
        dir.mkdirs();
        for (int i = 1; i <= SAVED_FAILURE_PLAN_MAX; i++) {
            var failureFile = new File(dir, "failure-%d.json".formatted(i));
            try {
                if (failureFile.createNewFile()) {
                    JSON.std.write(plan, failureFile);
                    return failureFile.getAbsolutePath();
                }
            } catch (IOException | JacksonException e) {
                err.printf("Unable to save plan '%s': %s%n", failureFile.getAbsolutePath(), e.getMessage());
                return null;
            }
        }
        err.println("There 'failures' directory is full, no more failure will be saved.");
        return null;
    }

    private Moment selectInterestingMomentOrNull() {
        var eligible = new HashSet<>(interestingSequences.values());
        eligible.removeIf(m -> runningTimelines.containsKey(m.timeline.id()));
        double sumIntensity = eligible.stream().mapToDouble(Moment::pheromones).sum();
        if (sumIntensity == 0.0) {
            return null;
        }
        double pick = random.nextDouble(0, sumIntensity);
        double intensity = 0.0;
        for (var moment : eligible) {
            intensity += moment.pheromones();
            if (intensity >= pick) {
                return moment;
            }
        }
        return null;
    }

    private static final class BloomFilter {
        private final BitSet bits;
        private final int numberOfBits;
        private final int numberOfHashes;

        BloomFilter(int expectedElements, double fpp) {
            this.numberOfBits = (int) (-expectedElements * log(fpp) / (log(2) * log(2)));
            this.numberOfHashes = max(1, (int) round((double) numberOfBits / expectedElements * log(2)));
            this.bits = new BitSet(numberOfBits);
        }

        void add(long hash) {
            int h1 = (int) hash;
            int h2 = (int) (hash >>> 32);
            for (int i = 1; i <= numberOfHashes; i++) {
                int combinedHash = h1 + (i * h2);
                bits.set(abs(combinedHash % numberOfBits));
            }
        }

        boolean mightContain(long hash) {
            int h1 = (int) hash;
            int h2 = (int) (hash >>> 32);
            for (int i = 1; i <= numberOfHashes; i++) {
                int combinedHash = h1 + (i * h2);
                if (!bits.get(abs(combinedHash % numberOfBits))) {
                    return false;
                }
            }
            return true;
        }
    }
}
