package test;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demo case for loom-dev <a href="http://mail.openjdk.java.net/pipermail/loom-dev/2019-February/000387.html">5x Slower Context Switching Between Fiber-vs-Thread</a> discussion.
 *
 * <pre>
 * $ java SlowFiberReport.java
 * threads took 6255 ms
 * fibers took 27810 ms
 * </pre>
 */
public enum SlowFiberReport {;

    private static final boolean LOGGING_ENABLED = false;

    private static final int THREAD_COUNT = 1;

    private static final int WORKER_COUNT = 50;

    private static final int MESSAGE_PASSING_COUNT = 1_000_000;

    private static class Worker implements Runnable {

        private final Lock lock = new ReentrantLock();

        private final Condition notWaiting = lock.newCondition();

        private final int id;

        private final int[] sequences;

        private Worker next = null;

        private volatile boolean waiting = true;

        private int sequence;

        private Worker(int id, int[] sequences) {
            this.id = id;
            this.sequences = sequences;
        }

        @Override
        public void run() {
            for (; ; ) {
                log("[%2d] locking", id);
                lock.lock();
                try {
                    if (!waiting) {
                        log("[%2d] locking next", id);
                        next.lock.lock();
                        try {
                            log("[%2d] signaling next (sequence=%d)", id, sequence);
                            if (!next.waiting) {
                                String message = String.format("%s was expecting %s to be waiting", id, next.id);
                                throw new IllegalStateException(message);
                            }
                            next.sequence = sequence - 1;
                            next.waiting = false;
                            waiting = true;
                            next.notWaiting.signal();
                        } finally {
                            log("[%2d] unlocking next", id);
                            next.lock.unlock();
                        }
                        if (sequence <= 0) {
                            sequences[id] = sequence;
                            break;
                        }
                    }
                    await();
                } finally {
                    log("[%2d] unlocking", id);
                    lock.unlock();
                }
            }
        }

        private void await() {
            while (waiting) {
                try {
                    log("[%2d] awaiting", id);
                    notWaiting.await();
                    log("[%2d] woke up", id);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

    }

    public static void main(String[] args) throws Exception {

        long startTimeMillis = System.currentTimeMillis();
        runThreads();
        System.out.format("threads took %d ms%n", System.currentTimeMillis() - startTimeMillis);

        startTimeMillis = System.currentTimeMillis();
        runFibers();
        System.out.format("fibers took %d ms%n", System.currentTimeMillis() - startTimeMillis);

    }

    private static int[] runThreads() throws Exception {

        log("creating worker threads (WORKER_COUNT=%d)", WORKER_COUNT);
        int[] sequences = new int[WORKER_COUNT];
        Worker[] workers = new Worker[WORKER_COUNT];
        Thread[] threads = new Thread[WORKER_COUNT];
        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            Worker worker = new Worker(workerIndex, sequences);
            workers[workerIndex] = worker;
            threads[workerIndex] = new Thread(worker);
        }

        log("setting next worker pointers");
        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            workers[workerIndex].next = workers[(workerIndex + 1) % WORKER_COUNT];
        }

        log("starting threads");
        for (Thread thread : threads) {
            thread.start();
        }

        log("ensuring threads are started and waiting");
        for (Thread thread : threads) {
            // noinspection LoopConditionNotUpdatedInsideLoop, StatementWithEmptyBody
            while (thread.getState() != Thread.State.WAITING) ;
        }

        log("initiating the ring (MESSAGE_PASSING_COUNT=%d)", MESSAGE_PASSING_COUNT);
        Worker firstWorker = workers[0];
        firstWorker.lock.lock();
        try {
            firstWorker.sequence = MESSAGE_PASSING_COUNT;
            firstWorker.waiting = false;
            firstWorker.notWaiting.signal();
        } finally {
            firstWorker.lock.unlock();
        }

        log("waiting for threads to complete");
        for (Thread thread : threads) {
            thread.join();
        }

        log("returning populated sequences");
        return sequences;

    }

    private static int[] runFibers() {

        log("creating executor service (THREAD_COUNT=%d)", THREAD_COUNT);
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);

        log("creating workers (WORKER_COUNT=%d)", WORKER_COUNT);
        Worker[] workers = new Worker[WORKER_COUNT];
        Fiber[] fibers = new Fiber[WORKER_COUNT];
        int[] sequences = new int[WORKER_COUNT];
        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            workers[workerIndex] = new Worker(workerIndex, sequences);
            fibers[workerIndex] = Fiber.schedule(executorService, workers[workerIndex]);
        }

        log("setting \"next\" worker pointers");
        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            workers[workerIndex].next = workers[(workerIndex + 1) % WORKER_COUNT];
        }

        log("initiating the ring (MESSAGE_PASSING_COUNT=%d)", MESSAGE_PASSING_COUNT);
        Worker firstWorker = workers[0];
        firstWorker.lock.lock();
        try {
            firstWorker.sequence = MESSAGE_PASSING_COUNT;
            firstWorker.waiting = false;
            firstWorker.notWaiting.signal();
        } finally {
            firstWorker.lock.unlock();
        }

        log("waiting for workers to complete");
        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            Fiber fiber = fibers[workerIndex];
            fiber.awaitTermination();
        }

        log("shutting down the executor service");
        executorService.shutdown();

        log("returning populated sequences");
        return sequences;

    }

    private static synchronized void log(String fmt, Object... args) {
        if (LOGGING_ENABLED) {
            System.out.format(Instant.now() + " [" + Thread.currentThread().getName() + "] " + fmt + "%n", args);
        }
    }

}