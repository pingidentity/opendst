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
package java.util;

import java.lang.SimulatorThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A deterministic {@link Timer} replacement for code running under the OpenDST simulator.
 *
 * <p>Mirrors {@link Timer}'s public API, but the scheduling thread is a {@link SimulatorThread}
 * (virtual, attached to the current simulation node) rather than {@code TimerThread} (a real OS
 * platform thread). This eliminates the cross-boundary monitor interactions on the queue object
 * that otherwise let real-thread scheduling leak into the deterministic simulator schedule.
 *
 * <p>At build time, the OpenDST Maven plugin rewrites {@code new Timer(...)} call sites in
 * application bytecode to {@code new SimulatorTimer(...)}. Outside simulation, the original
 * {@link Timer}/{@code TimerThread} is unaffected.
 *
 * <p>The parent {@code Timer}'s no-start constructor ({@code Timer(String, boolean, int)})
 * initializes the parent's {@code queue}, {@code thread}, and {@code cleanup} fields but does
 * not start the parent {@code TimerThread}. Those parent fields are inert tombstones — the
 * parent's {@code TimerThread} stays in state {@code NEW}, and the {@code Cleaner}-registered
 * reaper acts on the parent's empty queue (no observable effect).
 *
 * <p><strong>Known limitation — the worker is freed only by {@link #cancel()} or run end.</strong>
 * The stock {@link Timer} relies on a {@code Cleaner}-registered reaper to stop its worker once the
 * {@code Timer} becomes unreachable, so an abandoned {@code Timer} (used and then dropped without
 * {@code cancel()}) does not leak its thread. That mechanism is deliberately not reproduced here, for
 * two reasons: (1) the worker captures {@code this} via {@code this::mainLoop}, so the
 * {@code SimulatorTimer} can never become phantom-reachable while the worker runs, and (2) GC timing
 * is non-deterministic — reaping on GC would reintroduce exactly the scheduling non-determinism this
 * class exists to remove. Instead, the worker's lifetime is bounded by the simulation run: when idle
 * it parks in an untimed {@code wait()} that schedules no event, so it neither keeps the run alive nor
 * appears on another node's waiting list, and it is discarded when the run ends. The practical
 * consequence is that a workload creating many {@code Timer}s within a single run without calling
 * {@code cancel()} accumulates parked workers against the per-node virtual-thread cap. Call
 * {@code cancel()} to release a worker before the run ends.
 */
public class SimulatorTimer extends Timer {

    /** Auto-generated names mirror {@link Timer}'s {@code "Timer-N"} convention. */
    private static final AtomicInteger nextSerialNumber = new AtomicInteger();

    /** Our own task queue, decoupled from the parent's unused {@code Timer.queue}. */
    private final TaskQueue simQueue = new TaskQueue();

    /** The deterministic worker that drives {@link #mainLoop()}. */
    private final Thread worker;

    /**
     * Mirrors {@code TimerThread.newTasksMayBeScheduled}: set to {@code false} by
     * {@link #cancel()} to terminate the worker once the queue drains. Guarded by
     * {@code simQueue}'s monitor.
     */
    private boolean newTasksMayBeScheduled = true;

    public SimulatorTimer() {
        this("SimulatorTimer-" + nextSerialNumber.getAndIncrement(), false);
    }

    public SimulatorTimer(boolean isDaemon) {
        this("SimulatorTimer-" + nextSerialNumber.getAndIncrement(), isDaemon);
    }

    public SimulatorTimer(String name) {
        this(name, false);
    }

    @SuppressWarnings("this-escape")
    public SimulatorTimer(String name, boolean isDaemon) {
        // Patched Timer ctor: initializes queue/thread/cleanup fields but does NOT call thread.start().
        // The trailing 'int' arg is a marker distinguishing this from public Timer(String, boolean).
        super(name, isDaemon, 0);
        worker = new SimulatorThread(this::mainLoop, name);
        worker.start();
    }

    // ── Public Timer API, overridden to operate on simQueue ─────────────────

    @Override
    public void schedule(TimerTask task, long delay) {
        if (delay < 0) throw new IllegalArgumentException("Negative delay.");
        sched(task, System.currentTimeMillis() + delay, 0);
    }

    @Override
    public void schedule(TimerTask task, Date time) {
        sched(task, time.getTime(), 0);
    }

    @Override
    public void schedule(TimerTask task, long delay, long period) {
        if (delay < 0) throw new IllegalArgumentException("Negative delay.");
        if (period <= 0) throw new IllegalArgumentException("Non-positive period.");
        sched(task, System.currentTimeMillis() + delay, -period);
    }

    @Override
    public void schedule(TimerTask task, Date firstTime, long period) {
        if (period <= 0) throw new IllegalArgumentException("Non-positive period.");
        sched(task, firstTime.getTime(), -period);
    }

    @Override
    public void scheduleAtFixedRate(TimerTask task, long delay, long period) {
        if (delay < 0) throw new IllegalArgumentException("Negative delay.");
        if (period <= 0) throw new IllegalArgumentException("Non-positive period.");
        sched(task, System.currentTimeMillis() + delay, period);
    }

    @Override
    public void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) {
        if (period <= 0) throw new IllegalArgumentException("Non-positive period.");
        sched(task, firstTime.getTime(), period);
    }

    @Override
    public void cancel() {
        synchronized (simQueue) {
            newTasksMayBeScheduled = false;
            simQueue.clear();
            simQueue.notify();
        }
    }

    @Override
    public int purge() {
        int result = 0;
        synchronized (simQueue) {
            for (int i = simQueue.size(); i > 0; i--) {
                if (simQueue.get(i).state == TimerTask.CANCELLED) {
                    simQueue.quickRemove(i);
                    result++;
                }
            }
            if (result != 0) simQueue.heapify();
        }
        return result;
    }

    // ── Internal scheduling, mirrors Timer.sched / TimerThread.mainLoop ─────

    private void sched(TimerTask task, long time, long period) {
        if (time < 0) throw new IllegalArgumentException("Illegal execution time.");
        if (Math.abs(period) > (Long.MAX_VALUE >> 1)) period >>= 1;
        synchronized (simQueue) {
            if (!newTasksMayBeScheduled)
                throw new IllegalStateException("Timer already cancelled.");
            synchronized (task.lock) {
                if (task.state != TimerTask.VIRGIN)
                    throw new IllegalStateException("Task already scheduled or cancelled");
                task.nextExecutionTime = time;
                task.period = period;
                task.state = TimerTask.SCHEDULED;
            }
            simQueue.add(task);
            if (simQueue.getMin() == task) simQueue.notify();
        }
    }

    private void mainLoop() {
        while (true) {
            try {
                TimerTask task;
                boolean taskFired;
                synchronized (simQueue) {
                    while (simQueue.isEmpty() && newTasksMayBeScheduled) simQueue.wait();
                    if (simQueue.isEmpty()) break;
                    long currentTime, executionTime;
                    task = simQueue.getMin();
                    synchronized (task.lock) {
                        if (task.state == TimerTask.CANCELLED) {
                            simQueue.removeMin();
                            continue;
                        }
                        currentTime = System.currentTimeMillis();
                        executionTime = task.nextExecutionTime;
                        if (taskFired = (executionTime <= currentTime)) {
                            if (task.period == 0) {
                                simQueue.removeMin();
                                task.state = TimerTask.EXECUTED;
                            } else {
                                simQueue.rescheduleMin(
                                        task.period < 0 ? currentTime - task.period
                                                        : executionTime + task.period);
                            }
                        }
                    }
                    if (!taskFired) simQueue.wait(executionTime - currentTime);
                }
                if (taskFired) task.run();
            } catch (InterruptedException ignored) {
            }
        }
    }
}
