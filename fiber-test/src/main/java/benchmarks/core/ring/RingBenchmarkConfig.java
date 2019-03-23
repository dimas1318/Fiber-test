package benchmarks.core.ring;


import benchmarks.core.PropertyHelper;

public enum RingBenchmarkConfig {;

    public static final int THREAD_COUNT = PropertyHelper.readIntegerPropertyGreaterThanOrEqualTo("ring.threadCount", "1", 1);

    public static final int WORKER_COUNT = PropertyHelper.readIntegerPropertyGreaterThanOrEqualTo("ring.workerCount", "60", 2);

    public static final int MESSAGE_PASSING_COUNT = PropertyHelper.readIntegerPropertyGreaterThanOrEqualTo("ring.messagePassingCount", "6000", 0);

}
