package test;

import benchmarks.*;
import benchmarks.core.ring.RingBenchmark;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openjdk.jmh.annotations.Mode.*;

/**
 * Benchmark tests of {@link RingBenchmark} realizations.
 */
public class Test {

    /**
     * Number of threads.
     */
    private static final int THREAD_COUNT = 1;

    /**
     * Number of warmup iterations.
     */
    private static final int WARMUP_ITERATIONS = 2;

    /**
     * Number of measurement iterations.
     */
    private static final int MEASUREMENT_ITERATIONS = 20;

    @State(Scope.Benchmark)
    public static class RingState {
        RingBenchmark javaThreadRingBenchmark = new JavaThreadRingBenchmark();
        RingBenchmark javaFiberRingBenchmark = new JavaFiberRingBenchmark();
    }

    @Benchmark
    @BenchmarkMode(AverageTime)
    @OutputTimeUnit(MILLISECONDS)
    @Group("JavaThreadRingBenchmark")
    public void testJavaThreadRing(RingState state) throws Exception {
//        state.javaThreadRingBenchmark.ringBenchmark();
        try (JavaThreadRingBenchmark benchmark = new JavaThreadRingBenchmark()) {
            benchmark.ringBenchmark();
        }
    }

    @Benchmark
    @BenchmarkMode(AverageTime)
    @OutputTimeUnit(MILLISECONDS)
    @Group("JavaFiberRingBenchmark")
    public void testJavaFiberRing(RingState state) throws Exception {
//        state.javaFiberRingBenchmark.ringBenchmark();
        try (JavaFiberRingBenchmark benchmark = new JavaFiberRingBenchmark()) {
            benchmark.ringBenchmark();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(Test.class.getName())
                .warmupIterations(WARMUP_ITERATIONS)
                .measurementIterations(MEASUREMENT_ITERATIONS)
                .forks(1)
                .threads(THREAD_COUNT)
                .resultFormat(ResultFormatType.JSON)
                .result("Threads" + THREAD_COUNT + "_W" + WARMUP_ITERATIONS + "_M" + MEASUREMENT_ITERATIONS + "sleep.json")
                .build();
        new Runner(options).run();
    }
}