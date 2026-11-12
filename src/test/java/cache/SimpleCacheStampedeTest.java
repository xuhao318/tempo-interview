package cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backs review issue 3 (cache stampede) with a measurement. {@link SimpleCache} is the code under review, copied
 * unchanged from {@code original/SimpleCache.md}.
 *
 * <p>Both tests use the same setup: {@value #THREADS} threads read one key while the backend load is still running.
 * The load is held open until every thread has read the cache, so the result does not depend on thread timing.
 */
class SimpleCacheStampedeTest {
    private static final int THREADS = 32;
    private static final String KEY = "hot-key";

    @Test
    void simpleCacheSendsEveryConcurrentMissToTheBackend() throws Exception {
        SimpleCache<String, String> cache = new SimpleCache<>();
        AtomicInteger backendCalls = new AtomicInteger();
        CountDownLatch allRead = new CountDownLatch(THREADS);
        CountDownLatch backendResponds = new CountDownLatch(1);

        // The only pattern the get/put API allows: check, then load, then put.
        runConcurrently(allRead, backendResponds, () -> {
            String value = cache.get(KEY);
            allRead.countDown();
            if (value == null) {
                value = load(backendCalls, backendResponds);
                cache.put(KEY, value);
            }
            return value;
        });

        assertEquals(THREADS, backendCalls.get(), "every thread that missed called the backend");
    }

    @Test
    void singleFlightLoadCallsTheBackendOnce() throws Exception {
        // The fix from the review: one future per key, so later readers wait for the load already in flight.
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
        AtomicInteger backendCalls = new AtomicInteger();
        CountDownLatch allRead = new CountDownLatch(THREADS);
        CountDownLatch backendResponds = new CountDownLatch(1);

        runConcurrently(allRead, backendResponds, () -> {
            CompletableFuture<String> mine = new CompletableFuture<>();
            CompletableFuture<String> existing = inFlight.putIfAbsent(KEY, mine);
            allRead.countDown();
            if (existing != null) {
                return existing.join();
            }
            // The load runs outside any map lock; failures would go to mine.completeExceptionally.
            mine.complete(load(backendCalls, backendResponds));
            return mine.join();
        });

        assertEquals(1, backendCalls.get(), "only the first reader called the backend");
    }

    /** Simulates a slow backend: counts the call, then blocks until the test lets it answer. */
    private static String load(AtomicInteger backendCalls, CountDownLatch backendResponds) throws InterruptedException {
        backendCalls.incrementAndGet();
        assertTrue(backendResponds.await(10, TimeUnit.SECONDS), "backend was never released");
        return "value";
    }

    /** Starts {@value #THREADS} readers, lets the backend answer once all of them have read, and checks every result. */
    private static void runConcurrently(CountDownLatch allRead, CountDownLatch backendResponds,
                                        Callable<String> reader) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            Future<?>[] results = new Future<?>[THREADS];
            for (int i = 0; i < THREADS; i++) {
                results[i] = pool.submit(reader);
            }
            assertTrue(allRead.await(10, TimeUnit.SECONDS), "readers did not all start");
            backendResponds.countDown();
            for (Future<?> result : results) {
                assertEquals("value", result.get(10, TimeUnit.SECONDS));
            }
        }
    }
}
