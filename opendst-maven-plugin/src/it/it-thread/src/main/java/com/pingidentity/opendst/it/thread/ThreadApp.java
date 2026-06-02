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
package com.pingidentity.opendst.it.thread;

import com.pingidentity.opendst.sdk.Assert;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;

/**
 * Exercises thread-related determinism guards under OpenDST simulation.
 *
 * <p>Tests three areas:
 * <ol>
 *   <li><strong>Platform thread shutdown hooks</strong> — {@link LogManager} initialization
 *       registers a {@code LogManager$Cleaner} shutdown hook (a platform thread). The agent
 *       must skip it to preserve determinism.</li>
 *   <li><strong>Thread subclasses</strong> — Direct and transitive {@code Thread} subclasses
 *       are rewritten to extend {@code SimulatorThread} and must run correctly under simulation
 *       (identity, instanceof, join, overridden run()).</li>
 *   <li><strong>{@code java.util.Timer}</strong> — {@code new Timer(...)} call sites are
 *       rewritten to construct {@code SimulatorTimer}, whose worker is a virtual
 *       {@code SimulatorThread} rather than the legacy {@code TimerThread} platform thread.
 *       One-shot and recurring schedules must fire, the worker must be virtual, and
 *       {@code cancel()} must stop subsequent firings.</li>
 * </ol>
 */
public final class ThreadApp {

    /** Direct Thread subclass with a named constructor. */
    static class WorkerThread extends Thread {
        private volatile boolean completed;

        WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            Assert.reachable("worker-run");

            // currentThread() must return this instance, and be an instanceof WorkerThread.
            var current = Thread.currentThread();
            Assert.always(current == this, "worker-identity");
            Assert.always(current instanceof WorkerThread, "worker-instanceof");

            completed = true;
        }
    }

    /** Transitive subclass: extends WorkerThread (not Thread directly). */
    static class SpecialWorkerThread extends WorkerThread {
        private volatile boolean specialCompleted;

        SpecialWorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            super.run();
            Assert.reachable("special-worker-run");

            Assert.always(
                    Thread.currentThread() instanceof SpecialWorkerThread, "special-worker-instanceof");
            specialCompleted = true;
        }
    }

    /** Thread subclass using the no-arg constructor. */
    static class SimpleThread extends Thread {
        private volatile boolean completed;

        @Override
        public void run() {
            Assert.reachable("simple-run");
            completed = true;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // ---- Shutdown hook guard ----
        // Force LogManager initialization inside the simulation context.
        // This triggers registration of LogManager$Cleaner (a platform thread shutdown hook)
        // through the intercepted Runtime.addShutdownHook() path.
        LogManager.getLogManager();
        Assert.reachable("shutdown-hook-completed");

        // ---- Thread subclasses ----

        // Direct Thread subclass with named constructor
        var worker = new WorkerThread("test-worker");
        worker.start();
        worker.join(5000);
        Assert.always(worker.completed, "worker-joined");

        // Transitive subclass
        var special = new SpecialWorkerThread("special-worker");
        special.start();
        special.join(5000);
        Assert.always(special.specialCompleted, "special-joined");

        // No-arg constructor subclass
        var simple = new SimpleThread();
        simple.start();
        simple.join(5000);
        Assert.always(simple.completed, "simple-joined");

        // ---- java.util.Timer redirect ----
        // new Timer(...) call sites are rewritten to SimulatorTimer; the worker driving
        // scheduled TimerTasks must be a virtual SimulatorThread, not a platform TimerThread.

        // The result of new Timer(...) is statically a Timer reference — instanceof Timer
        // must still hold after the redirect rewrite.
        var timer = new Timer("test-timer");
        Assert.always(timer instanceof Timer, "timer-is-timer");

        // One-shot schedule: capture the worker thread's identity so we can assert it is virtual.
        var oneShotFired = new AtomicInteger(0);
        var workerWasVirtual = new AtomicBoolean(false);
        timer.schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        workerWasVirtual.set(Thread.currentThread().isVirtual());
                        oneShotFired.incrementAndGet();
                    }
                },
                50);

        // Recurring schedule: must fire at least 3 times before cancel.
        var recurringFired = new AtomicInteger(0);
        timer.scheduleAtFixedRate(
                new TimerTask() {
                    @Override
                    public void run() {
                        recurringFired.incrementAndGet();
                    }
                },
                10,
                20);

        // Sleep long enough that one-shot fires and recurring fires several times.
        Thread.sleep(200);

        // One-shot must fire exactly once on a virtual worker thread.
        Assert.always(oneShotFired.get() == 1, "timer-oneshot-fired");
        Assert.always(workerWasVirtual.get(), "timer-worker-virtual");

        // Recurring must fire several times before cancel.
        int recurringBeforeCancel = recurringFired.get();
        Assert.alwaysGreaterThanOrEqualTo(recurringBeforeCancel, 3, "timer-recurring-fired");

        // Cancel must stop subsequent firings.
        timer.cancel();
        Thread.sleep(100);
        Assert.always(recurringFired.get() == recurringBeforeCancel, "timer-cancelled");

        Assert.reachable("all-done");
    }
}
