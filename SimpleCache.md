## Code Review

You are reviewing the following code submitted as part of a task to implement an item cache in a highly concurrent application. The anticipated load includes: thousands of reads per second, hundreds of writes per second, tens of concurrent threads.
Your objective is to identify and explain the issues in the implementation that must be addressed before deploying the code to production. Please provide a clear explanation of each issue and its potential impact on production behaviour.

```java
import java.util.concurrent.ConcurrentHashMap;

public class SimpleCache<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMs = 60000; // 1 minute

    public static class CacheEntry<V> {
        private final V value;
        private final long timestamp;

        public CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public V getValue() {
            return value;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() - entry.getTimestamp() < ttlMs) {
                return entry.getValue();
            }
        }
        return null;
    }

    public int size() {
        return cache.size();
    }
}
```

---

## Review

Line numbers (`L…`) count from the first line of the code block above (`import` = L1).
Issues are ordered by severity. **Blocker** = must be fixed before production; **Major** = will cause incidents or
wrong behaviour under the stated load; **Minor** = quality / operability.

### Summary

1. Expired entries are never removed — unbounded memory growth (Blocker)
2. No size bound or eviction policy (Blocker)
3. No atomic load — cache stampede on a miss or expiry (Major)
4. Lost update: a slow loader can overwrite newer data (Major)
5. Wall-clock time used for expiry (Major)
6. No invalidation API (Major)
7. Ambiguous `null` and unchecked null keys (Major)
8. `size()` is misleading (Minor)
9. Hard-coded TTL and clock (Minor)
10. No observability (Minor)
11. Shared mutable values (Minor, depends on `V`)
12. Leaky API and unclear expiry semantics (Minor)

Details for each issue follow; a recommended replacement is at the end.

### 1. Expired entries are never removed — unbounded memory growth (Blocker)

**Lines:** L32–36 (`get` skips an expired entry but never removes it), L4 (the map that keeps it)

`get()` ignores an expired entry but leaves it in the map, and nothing else ever removes entries. Every distinct key
ever written stays on the heap forever. At hundreds of writes per second with a changing key space, that is on the
order of a million dead entries per hour (e.g. 300 writes/s × 3600 s ≈ 1.1M).

**Impact:** steadily growing heap, longer and more frequent GC pauses (latency spikes for every request), and
eventually `OutOfMemoryError` and a crashed service. The leak is slow, so it passes tests and short load tests and
shows up days after deployment.

When expiry cleanup is added, it must be race-safe: `cache.remove(key)` after seeing a stale entry can delete a fresh
value that another thread `put` in between. Use the conditional `cache.remove(key, staleEntry)`, which only removes
the entry if it is still the stale one. Cleanup on read alone is not enough (keys that are never read again are never
cleaned), so expired entries also need periodic sweeping — or, better, a library that does it (see Recommendation).
A sweeper thread must be a daemon thread or be stopped by a `close()` method; otherwise every cache instance leaks a
thread and can keep the JVM from shutting down.

### 2. No size bound or eviction policy (Blocker)

**Lines:** L4 (unbounded map), L25–27 (`put` adds without any limit)

Even with expiry fixed, the cache can hold everything written in the last 60 seconds with no upper limit. A burst of
traffic, a batch job, or a caller using high-cardinality keys (user input, request IDs) can fill the heap within one
TTL window.

**Impact:** memory usage is controlled by traffic patterns instead of configuration, so a traffic spike becomes an
`OutOfMemoryError`. Keys derived from user input make this exploitable. A maximum size (entries or weight) with an
eviction policy (LRU / LFU) is required.

### 3. No atomic load — cache stampede on a miss or expiry (Major)

**Lines:** L25–37 (the API is only `put` / `get`, so loading is left to callers)

The API only offers `get` and `put`, so every caller must write *check-then-act* code:
`v = cache.get(k); if (v == null) { v = load(k); cache.put(k, v); }`. This is not atomic. When a hot key expires,
every thread that reads it during the load time misses and calls the backend. At thousands of reads per second, one
expiry can send dozens to hundreds of identical requests to the database at the same instant ("thundering herd").
Entries written in a burst also expire at around the same time, so the herd can hit many keys at once.

**Impact:** periodic load spikes on the backing store every TTL, which can cascade into timeouts and an outage of the
database the cache was meant to protect.

**Fix:** offer `get(key, loader)` with *single-flight* loading — one thread loads, the others wait for its result.
`ConcurrentHashMap.computeIfAbsent` is the obvious tool but has three traps here:

- it does not replace an **expired** entry, because the key is still present (needs `compute` with an expiry check);
- it holds an internal bin lock while the loader runs, so a slow loader (a DB call) also blocks writes to unrelated
  keys that hash to the same bin;
- a loader that touches the same map can fail with `IllegalStateException: Recursive update`.

Storing a `CompletableFuture<V>` per key and running the load outside the map lock avoids all three. Adding random
jitter to the TTL spreads out expiries, and refreshing hot entries shortly before they expire avoids the herd
entirely.

**Measured:** `src/test/java/cache/SimpleCacheStampedeTest.java` runs 32 threads that read one key while its load is
in progress. This `SimpleCache` sends all 32 to the backend; a per-key `CompletableFuture` sends one.

### 4. Lost update: a slow loader can overwrite newer data (Major)

**Lines:** L26 (unconditional `put`, last writer wins)

`put` is last-writer-wins with no notion of which value is newer. Example:
thread A reads `v1` from the database (slow); thread B updates the database to `v2` and calls `put(k, v2)`; thread A
finishes and calls `put(k, v1)`. The cache now serves the outdated `v1` for the next 60 seconds, even though the
database has `v2`.

**Impact:** silently stale data under concurrent writes — exactly the situation of hundreds of writes per second.
Invalidating on write is not enough on its own: if callers still `put` after loading, thread A can `put(v1)` after
thread B's invalidate. Fix by loading only through the cache (single-flight, see 3), so that an invalidate on write
also discards a load still in flight; or by versioning values and only replacing with a newer version.

### 5. Wall-clock time used for expiry (Major)

**Lines:** L26 and L32 (`System.currentTimeMillis()`)

`System.currentTimeMillis()` is the wall clock, which can jump: NTP corrections, VM migration/resume, manual changes.
A backwards jump makes entries live longer than the TTL (up to the size of the jump); a forward jump expires the whole
cache at once.

**Impact:** stale data served far longer than the TTL, or a sudden full-cache expiry that triggers the stampede in 3
across every key. Elapsed time should be measured with the monotonic `System.nanoTime()` (comparing differences,
never absolute values).

### 6. No invalidation API (Major)

**Lines:** L25–41 (the public API: no `remove` / `invalidate` / `clear`)

There is no `remove`, `invalidate` or `clear`. When the source data changes, the application cannot evict the old
value and must serve it until the TTL runs out.

**Impact:** up to 60 seconds of stale reads after every update, with no way to fix a known-bad entry (or the whole
cache) during an incident short of restarting the service.

### 7. Ambiguous `null` and unchecked null keys (Major)

**Lines:** L26 (a `null` value is accepted), L30 (a `null` key throws), L33 and L36 (both can return `null`)

`get` returns `null` for three different cases: key never cached, entry expired, and a cached `null` value
(`put(k, null)` is accepted, because the map stores the non-null `CacheEntry` wrapper). Callers cannot tell them apart,
so they cannot cache "not found" results — every lookup of a missing item goes to the backend on every request.
A `null` key, by contrast, throws `NullPointerException` from `ConcurrentHashMap`, which is not documented.

**Impact:** repeated backend calls for absent items (a cheap way to bypass the cache under load), and caller bugs from
the unclear contract. Decide the contract explicitly: reject nulls with a clear message, or return `Optional<V>` /
support an explicit negative-cache marker.

### 8. `size()` is misleading (Minor)

**Lines:** L39–41

`size()` counts expired entries, so it reports what the leak in 1 holds rather than what the cache can serve. In
addition, `ConcurrentHashMap.size()` is only an estimate while other threads modify the map, and because it returns
an `int` it stops at `Integer.MAX_VALUE`; the Javadoc recommends `mappingCount()` instead.

**Impact:** dashboards, alerts and capacity decisions based on `size()` are wrong; the number grows forever.

### 9. Hard-coded TTL and clock (Minor)

**Lines:** L5 (hard-coded TTL), L26 and L32 (clock called directly)

`ttlMs` is a constant (and an instance field rather than `static final`), so different data cannot have different
lifetimes and the value cannot be tuned without a code change. The time source is called directly, so expiry can only
be tested by sleeping for a minute.

**Impact:** no operational tuning, slow or missing tests for the most important behaviour. Pass the TTL (as a
`Duration`) and a time source (e.g. a `LongSupplier` ticker) through the constructor.

### 10. No observability (Minor)

**Lines:** L25–37 (no counters in `put` / `get`)

There are no hit / miss / eviction / load-time counters.

**Impact:** in production nobody can tell whether the cache works — a 0% hit rate or a stampede looks the same as
normal operation until the database falls over.

### 11. Shared mutable values (Minor, depends on `V`)

**Lines:** L33 (returns the shared instance)

`get` returns the same instance to every thread. The `final` fields in `CacheEntry` guarantee that the entry and the
value as constructed are safely published, but not later changes. If `V` is mutable, one caller modifying the result
changes it for all callers, and other threads may observe partial updates (a data race).

**Impact:** hard-to-reproduce corruption of cached data. Require immutable values (document it), or return copies.

### 12. Leaky API and unclear expiry semantics (Minor)

**Lines:** L7–23 (public `CacheEntry`), L26 and L32 (expire-after-write only)

`CacheEntry` is a public nested class with a public constructor, exposing an implementation detail that no public
method needs; it should be a private `record`. The TTL is expire-after-write and reads do not extend it — a reasonable
choice, but undocumented, so a hot key is reloaded every minute even though it is read constantly.

**Impact:** callers may depend on internals, and the reload behaviour surprises the team during incidents.

### Recommendation: use a proven cache library

**Lines:** L3–42 (whole class)

A correct concurrent cache needs bounded size, efficient eviction, race-free expiry, single-flight loading, refresh,
and statistics. These are hard to get right and are already solved by [Caffeine](https://github.com/ben-manes/caffeine):

```java
LoadingCache<K, V> cache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(Duration.ofMinutes(1))
        .refreshAfterWrite(Duration.ofSeconds(50))
        .recordStats()
        .build(key -> loadFromBackend(key));
```

This addresses 1–3, 5 (it uses a monotonic ticker), 6, 8–10 and 12 out of the box. One caveat on 3: the synchronous
`LoadingCache` also runs the loader inside `ConcurrentHashMap.compute`, so a slow loader holds a bin lock just as
described there. For slow loaders (remote calls) use `.buildAsync(...)` (`AsyncLoadingCache`), which stores a future
per key and runs the load outside the map lock.

### What is already fine

**Lines:** L4 (`ConcurrentHashMap`), L8–9 (`final` fields), L32 (time comparison)

- Individual `get` / `put` calls are thread-safe: `ConcurrentHashMap` gives lock-free reads and fine-grained write
  locking, which suits tens of threads.
- `CacheEntry` is immutable with `final` fields, so entries are safely published between threads and a reader never
  sees a half-built entry.
- `now - timestamp < ttl` compares a difference rather than absolute times, which is the form that stays correct
  after switching to `System.nanoTime()` (whose values can overflow).
