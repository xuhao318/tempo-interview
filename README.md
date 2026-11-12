# Senior Developer Assessment

[![CI](https://github.com/xuhao318/tempo-interview/actions/workflows/ci.yml/badge.svg)](https://github.com/xuhao318/tempo-interview/actions/workflows/ci.yml)

- **Task 1** — Hierarchy filter: `src/main/java/hierarchy/HierarchyFilter.java`, tests in `src/test/java/hierarchy/HierarchyFilterTest.java`.
- **Task 2** — Code review: [`SimpleCache.md`](SimpleCache.md).

The original assignment files (`Senior_Developer_Assessment.md`, `Hierarchy.java`, `SimpleCache.md`) are kept
unchanged in `original/` for reference. The solution splits `original/Hierarchy.java` into `Hierarchy`,
`ArrayBasedHierarchy` and `HierarchyFilter` under `src/main/java`, with the tests under `src/test/java`.

## Project layout

```
original/                          assignment files, unchanged
src/main/java/hierarchy/
  Hierarchy.java                   the Hierarchy interface (copied from the original, made public)
  ArrayBasedHierarchy.java         array-backed implementation (copied, plus an array-length check)
  HierarchyFilter.java             Task 1 solution: filter()
src/test/java/hierarchy/
  HierarchyFilterTest.java         JUnit 5 tests (includes the original FilterTest example)
src/test/java/cache/
  SimpleCache.java                 the code under review, unchanged apart from a package line (test-only)
  SimpleCacheStampedeTest.java     measures review issue 3: backend calls under concurrent misses
.github/workflows/ci.yml           runs ./mvnw test on JDK 21 for every push and pull request
SimpleCache.md                     Task 2 solution: the original task and code, followed by the review
pom.xml                            Maven build: Java 21, JUnit 5
mvnw, mvnw.cmd, .mvn/wrapper/      Maven wrapper, downloads Maven on first run
```

The original single file was split because Java allows only one public top-level class per file, and Maven keeps
tests (`src/test`) apart from production code (`src/main`). Apart from the `hierarchy` package and `public`
modifiers, the only change to the provided classes is that `ArrayBasedHierarchy` rejects `nodeIds`/`depths` arrays of different lengths.

## Running locally

**Prerequisites:** JDK 21 on the `PATH` or in `JAVA_HOME` (check with `java -version`). Maven does not need to be
installed: on the first run the wrapper downloads Maven 3.9.9 and the JUnit dependencies into `~/.m2`, so that run
needs internet access.

```sh
git clone https://github.com/xuhao318/tempo-interview.git
cd tempo-interview

./mvnw test                                  # all tests (Linux / macOS); use mvnw.cmd on Windows
./mvnw test -Dtest=HierarchyFilterTest       # Task 1 tests only
./mvnw test -Dtest=SimpleCacheStampedeTest   # Task 2 stampede test only
```

A successful run ends with `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. Per-test reports
are written to `target/surefire-reports/`. The same `./mvnw test` runs in CI on every push (badge above).

## Task 1: approach

A node is kept iff it and all of its ancestors pass the predicate. In DFS order, a node's subtree is the run of
nodes after it with a greater depth, so the filter is a single pass that remembers the depth of the rejected
node it is currently inside (`skipDepth`):

- `depth > skipDepth` → descendant of a rejected node, skip it without calling the predicate;
- otherwise we have left any rejected subtree; test the node, keep it or start skipping its subtree.

A kept node has all its ancestors kept, so **its depth is unchanged** in the output — no depth recalculation is needed.

- Time O(n), extra space O(n) (output arrays, trimmed once at the end). No recursion, so very deep trees are safe.
- The predicate is called at most once per node and never for descendants of a rejected node.

## Task 1: design trade-offs

- **Eager copy vs. lazy view.** `filter` returns a materialised copy. A lazy view (wrapping the input and skipping
  rejected subtrees on access) would save memory, but `size()` and `nodeId(i)` need random access, so a view must either
  rescan on every call or build an index of kept positions, which costs the same O(n) as the copy. A copy also makes
  the result independent of later changes to the input and keeps the predicate calls in one predictable place.
- **Allocate once, trim once.** The output arrays are allocated at the input size and trimmed at the end. Peak memory
  is up to 2n ints per array, in exchange for no resizing inside the loop. A growable buffer would lower the peak when
  most nodes are rejected; for the expected case (most nodes kept) one allocation plus one copy is cheaper.
- **No validation of the input.** Checking the depth invariants is O(n) and would double the work of every call to
  catch a bug in whoever built the hierarchy. That check belongs where hierarchies are constructed, not in every
  consumer. The behaviour on invalid input is documented instead.
- **Scaling further.** Trees in a forest are independent, so a very large forest can be split at depth-0 boundaries and
  filtered in parallel, then concatenated. The loop also reads the input strictly front to back and keeps O(1) state
  (`skipDepth`), so the same loop would filter a streamed hierarchy that does not fit in memory; only the up-front
  allocation from `size()` would change to a growable or streamed output. Neither is needed at this size, so neither
  is implemented.

## Task 1: assumptions

- The input satisfies the depth invariants documented on `Hierarchy` (first depth is 0, depth increases by at most
  1 between consecutive nodes). The filter does not validate them; an invalid input gives an equally invalid output.
- Node IDs are unique (as documented); the filter itself does not depend on it.
- The predicate is deterministic and side-effect free, and is evaluated in DFS order.
- `null` arguments throw `NullPointerException`.
- The result is a new hierarchy independent of the input; the input is never modified.
- `ArrayBasedHierarchy` now rejects arrays of different lengths. It still does not copy its arrays (as in the
  original), so callers must not modify them after construction.

## Task 1: tests

`./mvnw test` runs 22 tests: 20 for Task 1 (below) and 2 for Task 2 (see [Task 2](#task-2-code-review)).

Task 1: example from the task, empty input, accept-all / reject-all, single node, rejected root / inner node / leaf /
last tree, passing node under a rejected ancestor, jumping back several levels after a skipped subtree, consecutive
rejected siblings, flat forest, predicate not invoked for skipped descendants, input not modified, a 100,000-deep
chain, null arguments, mismatched array lengths, and a randomized comparison against a simple reference
implementation over 10,000 random valid forests.

## Task 2: code review

The review is in [`SimpleCache.md`](SimpleCache.md), below the original task text and code. The original file is kept
unchanged in `original/SimpleCache.md`.

**Approach.** Each method was traced against the stated load (thousands of reads/s, hundreds of writes/s, tens of
threads) with three questions: what happens to memory over hours, what happens when threads race on the same key,
and what callers are forced to write around the API. Every finding was then ranked by production impact, not by the
order it appears in the code. Claims that depend on timing were checked with a test where that could be done
deterministically (issue 3).

**Assumptions.**

- The cache sits in front of a slower backend (a database or remote service), and callers load from it on a miss.
- The key space changes over time (for example, keyed by user or request data), so the set of keys ever written is
  much larger than the set that is live at once. This is what turns issue 1 into an out-of-memory failure.
- It is an in-process cache in a single JVM; consistency across several service instances is out of scope.
- The 60-second TTL is meant as expire-after-write, as the code implements it.
- Adding a well-known library dependency (Caffeine) is acceptable in production code.

**Summary of the review:**

- A numbered summary of 12 issues, ordered by severity (Blocker / Major / Minor), judged against the stated load:
  thousands of reads/s, hundreds of writes/s, tens of threads.
- Each issue cites the lines of the code under review (`L…`, counted from the first line of the code block) and
  explains the problem, its production impact, and the fix.
- The blockers: expired entries are never removed (memory grows until `OutOfMemoryError`), and the cache has no size
  bound. The main concurrency issues: cache stampede on expiry, lost updates from slow loaders, wall-clock expiry.
- Issue 3 is backed by a test, `SimpleCacheStampedeTest`: with 32 threads reading one key while it loads, the original
  `SimpleCache` calls the backend 32 times; the single-flight fix from the review calls it once. The load is held open
  until every thread has read the cache, so the result does not depend on thread timing.
- It ends with a recommendation (use Caffeine, with `AsyncLoadingCache` for slow loaders) and a short list of what the
  code already gets right.
