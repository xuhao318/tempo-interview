package cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backs review issue 3 (cache stampede) with a measurement. {@link SimpleCache} is the code under review, copied
 * unchanged from {@code original/SimpleCache.md}.
 *
 * <p>All tests use the same setup: {@value #THREADS} threads read one key while the backend load is still running.
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
        Future<String>[] results = runConcurrently(allRead, backendResponds, () -> {
            String value = cache.get(KEY);
            allRead.countDown();
            if (value == null) {
                value = load(backendCalls, backendResponds);
                cache.put(KEY, value);
            }
            return value;
        });

        assertAllReturn("value", results);
        assertEquals(THREADS, backendCalls.get(), "every thread that missed called the backend");
    }

    @Test
    void singleFlightLoadCallsTheBackendOnce() throws Exception {
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
        AtomicInteger backendCalls = new AtomicInteger();
        CountDownLatch allRead = new CountDownLatch(THREADS);
        CountDownLatch backendResponds = new CountDownLatch(1);

        Future<String>[] results = runConcurrently(allRead, backendResponds,
            () -> singleFlightGet(inFlight, allRead::countDown, () -> load(backendCalls, backendResponds)));

        assertAllReturn("value", results);
        assertEquals(1, backendCalls.get(), "only the first reader called the backend");
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // without the fix, waiters block forever
    void singleFlightFailedLoadFailsEveryWaiterAndIsNotCached() throws Exception {
        ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
        AtomicInteger backendCalls = new AtomicInteger();
        CountDownLatch allRead = new CountDownLatch(THREADS);
        CountDownLatch backendResponds = new CountDownLatch(1);

        Future<String>[] results = runConcurrently(allRead, backendResponds,
            () -> singleFlightGet(inFlight, allRead::countDown, () -> {
                load(backendCalls, backendResponds);
                throw new IllegalStateException("backend down");
            }));

        for (Future<String> result : results) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals("backend down", rootCause(e).getMessage(), "every waiter sees the load failure");
        }
        assertEquals(1, backendCalls.get(), "only the first reader called the backend");
        assertTrue(inFlight.isEmpty(), "the failed load is not cached");
        assertEquals("recovered", singleFlightGet(inFlight, () -> { }, () -> "recovered"), "the next read retries");
    }

    /**
     * The fix from the review: one future per key, so later readers wait for the load already in flight instead of
     * calling the backend. The load runs outside any map lock.
     */
    private static String singleFlightGet(ConcurrentHashMap<String, CompletableFuture<String>> inFlight,
                                          Runnable afterLookup, Callable<String> loader) throws Exception {
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> existing = inFlight.putIfAbsent(KEY, mine);
        afterLookup.run();
        if (existing != null) {
            return existing.join();
        }
        try {
            mine.complete(loader.call());
        } catch (Exception e) {
            // Evict before failing the waiters, so a retry they trigger starts a fresh load instead of reusing this one.
            inFlight.remove(KEY, mine);
            mine.completeExceptionally(e);
            throw e;
        }
        return mine.join();
    }

    /** Simulates a slow backend: counts the call, then blocks until the test lets it answer. */
    private static String load(AtomicInteger backendCalls, CountDownLatch backendResponds) throws InterruptedException {
        backendCalls.incrementAndGet();
        assertTrue(backendResponds.await(10, TimeUnit.SECONDS), "backend was never released");
        return "value";
    }

    /** Starts {@value #THREADS} readers, lets the backend answer once all of them have read, and returns their results. */
    @SuppressWarnings("unchecked")
    private static Future<String>[] runConcurrently(CountDownLatch allRead, CountDownLatch backendResponds,
                                                    Callable<String> reader) throws InterruptedException {
        Future<String>[] results = new Future[THREADS];
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                results[i] = pool.submit(reader);
            }
            assertTrue(allRead.await(10, TimeUnit.SECONDS), "readers did not all start");
            backendResponds.countDown();
        }
        return results;
    }

    private static void assertAllReturn(String expected, Future<String>[] results) throws Exception {
        for (Future<String> result : results) {
            assertEquals(expected, result.get(10, TimeUnit.SECONDS));
        }
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
