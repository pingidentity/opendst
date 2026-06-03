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
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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

        // CompletableFuture.*Async() without an explicit executor — runs stage tasks on the common
        // pool, which the agent reroutes to a node-scheduled virtual thread.
        var cfRanOnWorker = new AtomicBoolean(false);
        String cfResult = CompletableFuture.supplyAsync(() -> {
                    if (onForkJoinWorker()) {
                        cfRanOnWorker.set(true);
                    }
                    return "x";
                })
                .thenApplyAsync(s -> {
                    if (onForkJoinWorker()) {
                        cfRanOnWorker.set(true);
                    }
                    return s + "y";
                })
                .join();
        Assert.always("xy".equals(cfResult), "completablefuture-correct");
        Assert.always(!cfRanOnWorker.get(), "completablefuture-no-worker");

        // Arrays.parallelSort(Object[], Comparator) on an array larger than the parallel grain size —
        // the agent redirects it to the sequential sort, so the comparator never runs on a worker.
        Integer[] arr = new Integer[20_000];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = arr.length - i;
        }
        var sortRanOnWorker = new AtomicBoolean(false);
        Arrays.parallelSort(arr, (x, y) -> {
            if (onForkJoinWorker()) {
                sortRanOnWorker.set(true);
            }
            return Integer.compare(x, y);
        });
        boolean comparatorSorted = true;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != i + 1) {
                comparatorSorted = false;
                break;
            }
        }
        Assert.always(comparatorSorted, "arrays-parallelsort-correct");
        Assert.always(!sortRanOnWorker.get(), "arrays-parallelsort-no-worker");

        // A primitive parallelSort overload (int[]) is redirected to the sequential sort too.
        int[] prim = new int[20_000];
        for (int i = 0; i < prim.length; i++) {
            prim[i] = prim.length - i;
        }
        Arrays.parallelSort(prim);
        boolean primitiveSorted = true;
        for (int i = 0; i < prim.length; i++) {
            if (prim[i] != i + 1) {
                primitiveSorted = false;
                break;
            }
        }
        Assert.always(primitiveSorted, "arrays-parallelsort-primitive-correct");

        // ConcurrentHashMap bulk forEach with threshold 1 (would normally fork) — runs in the caller.
        var chm = new ConcurrentHashMap<Integer, Integer>();
        for (int i = 0; i < 1000; i++) {
            chm.put(i, i);
        }
        var chmRanOnWorker = new AtomicBoolean(false);
        var chmSum = new LongAdder();
        chm.forEach(1, (k, v) -> {
            if (onForkJoinWorker()) {
                chmRanOnWorker.set(true);
            }
            chmSum.add(v);
        });
        Assert.always(chmSum.sum() == 499_500, "chm-bulk-correct");
        Assert.always(!chmRanOnWorker.get(), "chm-bulk-no-worker");

        Assert.reachable("all-done");
    }
}
