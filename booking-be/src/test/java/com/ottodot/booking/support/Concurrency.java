package com.ottodot.booking.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Runs N tasks that all start at the same instant, to maximise contention. */
public final class Concurrency {

    private Concurrency() {
    }

    /**
     * A plain start latch lets early threads finish before later ones begin,
     * which quietly turns a "race" into a sequence and can make a broken
     * implementation pass. A CyclicBarrier releases every thread only once all
     * of them have arrived, so they genuinely collide on the same row.
     */
    public static <T> List<T> inParallel(int threads, IntFunction<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier gate = new CyclicBarrier(threads);
        try {
            List<Future<T>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    return task.apply(index);
                }));
            }
            List<T> results = new ArrayList<>(threads);
            for (Future<T> f : futures) {
                try {
                    results.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new IllegalStateException("task failed unexpectedly", e.getCause());
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
