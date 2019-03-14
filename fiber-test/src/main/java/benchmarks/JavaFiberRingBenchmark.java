package benchmarks;

import benchmarks.core.SingletonSynchronizer;
import benchmarks.core.ring.RingBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static benchmarks.core.StdoutLogger.log;
import static benchmarks.core.ring.RingBenchmarkConfig.*;

@State(Scope.Benchmark)
public class JavaFiberRingBenchmark implements RingBenchmark {

    private static final class Context implements AutoCloseable, Callable<int[]> {

        private final SingletonSynchronizer completionSynchronizer = new SingletonSynchronizer();

        private final int[] sequences = new int[WORKER_COUNT];

        private final ExecutorService executorService;

        private final JavaThreadRingBenchmark.Worker[] workers;

        private Context() {

            log("creating workers (WORKER_COUNT=%d)", WORKER_COUNT);
            this.workers = new JavaThreadRingBenchmark.Worker[WORKER_COUNT];
            CountDownLatch startLatch = new CountDownLatch(WORKER_COUNT);
            for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
                JavaThreadRingBenchmark.Worker worker = new JavaThreadRingBenchmark.Worker(workerIndex, startLatch, completionSynchronizer);
                workers[workerIndex] = worker;
            }

            log("setting next worker pointers");
            for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
                workers[workerIndex].next = workers[(workerIndex + 1) % WORKER_COUNT];
            }

            log("scheduling fibers (THREAD_COUNT=%d)", THREAD_COUNT);
            this.executorService = Executors.newFixedThreadPool(THREAD_COUNT);
            for (JavaThreadRingBenchmark.Worker worker : workers) {
                Fiber.schedule(executorService, worker);
            }

            log("waiting for fibers to start");
            try {
                startLatch.await();
            } catch (InterruptedException ignored) {
                log("start latch wait interrupted");
                Thread.currentThread().interrupt();
            }

        }

        @Override
        public void close() {
            log("shutting down the executor service");
            executorService.shutdown();
        }

        @Override
        public int[] call() {

            log("initiating the ring (MESSAGE_PASSING_COUNT=%d)", MESSAGE_PASSING_COUNT);
            JavaThreadRingBenchmark.Worker firstWorker = workers[0];
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
    public void close() {
        context.close();
    }

    @Override
    @Benchmark
    public int[] ringBenchmark() {
        return context.call();
    }

//    public static void main(String[] args) throws RunnerException {
//        Options options = new OptionsBuilder()
//                .include(JavaFiberRingBenchmark.class.getName())
//                .warmupIterations(WARMUP_ITERATIONS)
//                .measurementIterations(MEASUREMENT_ITERATIONS)
//                .forks(1)
//                .threads(THREAD_COUNT)
//                .resultFormat(ResultFormatType.JSON)
//                .result("Threads" + THREAD_COUNT + "_W" + WARMUP_ITERATIONS + "_M" + MEASUREMENT_ITERATIONS + "sleep.json")
//                .build();
//        new Runner(options).run();
//
//        try (JavaFiberRingBenchmark benchmark = new JavaFiberRingBenchmark()) {
//            benchmark.ringBenchmark();
//        }
//    }
}