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
package com.pingidentity.opendst.it.forkjoin;

import com.pingidentity.opendst.sdk.Assert;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Exercises parallel streams under OpenDST simulation (Phase 1 of ForkJoinPool determinism).
 *
 * <p>The agent's {@code StreamInterceptors} forces {@code AbstractPipeline.isParallel()} to
 * {@code false} inside a node, so a parallel stream's terminal operation runs sequentially on the
 * calling {@code SimulatorThread} (a virtual thread on the node's deterministic scheduler) instead
 * of submitting work to the shared {@code ForkJoinPool} common pool. No {@code -D} flag is needed.
 *
 * <p>The app asserts that parallel-stream work runs on a non-{@code ForkJoinWorkerThread} (i.e. the
 * simulated caller) and produces correct results. {@code verify.groovy} additionally asserts
 * determinism ("no internal error") and the absence of any escaped platform thread.
 */
public final class FjpApp {

    private static boolean onForkJoinWorker() {
        return Thread.currentThread() instanceof ForkJoinWorkerThread;
    }

    public static void main(String[] args) {
        var workerHits = new AtomicInteger();

        // IntStream.range(...).parallel() — primitive parallel stream.
        int sum = IntStream.range(0, 1000)
                .parallel()
                .map(i -> {
                    if (onForkJoinWorker()) {
                        workerHits.incrementAndGet();
                    }
                    return i;
                })
                .sum();
        Assert.always(sum == 499_500, "intstream-correct");

        // Collection.parallelStream() — reference parallel stream with a collecting terminal op.
        var values = IntStream.range(0, 1000).boxed().collect(Collectors.toList());
        long evens = values.parallelStream()
                .filter(i -> {
                    if (onForkJoinWorker()) {
                        workerHits.incrementAndGet();
                    }
                    return i % 2 == 0;
                })
                .count();
        Assert.always(evens == 500, "parallelstream-correct");

        // No stage of either stream may have run on a ForkJoinWorkerThread.
        Assert.always(workerHits.get() == 0, "streams-caller-ran");

        Assert.reachable("all-done");
    }
}
