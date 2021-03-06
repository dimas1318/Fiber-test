package benchmarks;

import benchmarks.core.SingletonSynchronizer;
import benchmarks.core.ring.RingBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import test.Test;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static benchmarks.core.StdoutLogger.log;
import static benchmarks.core.ring.RingBenchmarkConfig.*;
import static org.openjdk.jmh.runner.Defaults.MEASUREMENT_ITERATIONS;
import static org.openjdk.jmh.runner.Defaults.WARMUP_ITERATIONS;

/**
 * Ring benchmark using Java {@link Thread}s.
 */
@State(Scope.Benchmark)
public class JavaThreadRingBenchmark implements RingBenchmark {

    static class Worker implements Runnable {

        final Lock lock = new ReentrantLock();

        final Condition waitingCondition = lock.newCondition();

        final int id;

        final CountDownLatch startLatch;

        final SingletonSynchronizer completionSynchronizer;

        Worker next = null;

        boolean waiting = true;

        int sequence;

        Worker(int id, CountDownLatch startLatch, SingletonSynchronizer completionSynchronizer) {
            this.id = id;
            this.startLatch = startLatch;
            this.completionSynchronizer = completionSynchronizer;
        }

        @Override
        public void run() {
            startLatch.countDown();
            log("[%2d] locking", id);
            lock.lock();
            try {
                // noinspection InfiniteLoopStatement
                for (; ; ) {
                    if (!waiting) {
                        if (sequence <= 0) {
                            complete();
                        } else {
                            signalNext();
                        }
                    }
                    await();
                }
            } catch (InterruptedException ignored) {
                log("[%2d] interrupted", id);
                Thread.currentThread().interrupt();
            } finally {
                log("[%2d] unlocking", id);
                lock.unlock();
            }
        }

        private void complete() {
            log("[%2d] signaling completion (sequence=%d)", () -> new Object[]{id, sequence});
            waiting = true;
            completionSynchronizer.signal();
        }

        private void signalNext() {
            log("[%2d] locking next", id);
            next.lock.lock();
            try {
                log("[%2d] signaling next", id);
                if (!next.waiting) {
                    String message = String.format("%s was expecting %s to be waiting", id, next.id);
                    throw new IllegalStateException(message);
                }
                next.sequence = sequence - 1;
                next.waiting = false;
                waiting = true;
                next.waitingCondition.signal();
            } finally {
                log("[%2d] unlocking next", id);
                next.lock.unlock();
            }
        }

        private void await() throws InterruptedException {
            while (waiting) {
                log("[%2d] awaiting", id);
                waitingCondition.await();
                log("[%2d] woke up (sequence=%d)", () -> new Object[]{id, sequence});
            }
        }

    }

    private static final class Context implements AutoCloseable, Callable<int[]> {

        private final SingletonSynchronizer completionSynchronizer = new SingletonSynchronizer();

        private final int[] sequences = new int[WORKER_COUNT];

        private final Worker[] workers;

        private final Thread[] threads;

        private Context() {

            log("creating worker threads (WORKER_COUNT=%d)", WORKER_COUNT);
            this.workers = new Worker[WORKER_COUNT];
            this.threads = new Thread[WORKER_COUNT];
            CountDownLatch startLatch = new CountDownLatch(WORKER_COUNT);
            for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
                Worker worker = new Worker(workerIndex, startLatch, completionSynchronizer);
                workers[workerIndex] = worker;
                threads[workerIndex] = new Thread(worker, "Worker-" + workerIndex);
            }

            log("setting next worker pointers");
            for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
                workers[workerIndex].next = workers[(workerIndex + 1) % WORKER_COUNT];
            }

            log("starting threads");
            for (Thread thread : threads) {
                thread.start();
            }

            log("waiting for threads to start");
            try {
                startLatch.await();
            } catch (InterruptedException ignored) {
                log("start latch wait interrupted");
                Thread.currentThread().interrupt();
            }

        }

        @Override
        public void close() throws Exception {

            log("interrupting threads");
            for (Thread thread : threads) {
                thread.interrupt();
            }

            log("waiting for threads to complete");
            for (Thread thread : threads) {
                thread.join();
            }

        }

        @Override
        public int[] call() {

            log("initiating the ring (MESSAGE_PASSING_COUNT=%d)", MESSAGE_PASSING_COUNT);
            Worker firstWorker = workers[0];
            firstWorker.lock.lock();
            try {
                firstWorker.sequence = MESSAGE_PASSING_COUNT;
                firstWorker.waiting = false;
                firstWorker.waitingCondition.signal();
            } finally {
                firstWorker.lock.unlock();
            }

            log("waiting for completion");
            completionSynchronizer.await();

            log("collecting sequences");
            for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
                sequences[workerIndex] = workers[workerIndex].sequence;
            }

            log("returning populated sequences (sequences=%s)", () -> new Object[]{Arrays.toString(sequences)});
            return sequences;

        }

    }

    private final Context context = new Context();

    @Override
    @TearDown
    public void close() throws Exception {
        context.close();
    }

    @Override
    @Benchmark
    public int[] ringBenchmark() {
        return context.call();
    }

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder()
                .include(JavaThreadRingBenchmark.class.getName())
                .warmupIterations(WARMUP_ITERATIONS)
                .measurementIterations(MEASUREMENT_ITERATIONS)
                .forks(1)
                .threads(THREAD_COUNT)
                .resultFormat(ResultFormatType.JSON)
                .result("ThreadRealization" + THREAD_COUNT + "_W" + WARMUP_ITERATIONS + "_M" + MEASUREMENT_ITERATIONS + ".json")
                .build();
        new Runner(options).run();
//        try (JavaThreadRingBenchmark benchmark = new JavaThreadRingBenchmark()) {
//            benchmark.ringBenchmark();
//        }
    }
}
