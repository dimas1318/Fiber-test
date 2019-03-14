package benchmarks.core.ring;

public interface RingBenchmark extends AutoCloseable {

    int[] ringBenchmark() throws Exception;

}
