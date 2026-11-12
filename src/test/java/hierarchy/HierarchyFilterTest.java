package hierarchy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HierarchyFilterTest {

    private static Hierarchy hierarchy(int[] nodeIds, int[] depths) {
        return new ArrayBasedHierarchy(nodeIds, depths);
    }

    private static void assertFiltered(int[] nodeIds, int[] depths, IntPredicate predicate,
                                       int[] expectedIds, int[] expectedDepths) {
        Hierarchy actual = HierarchyFilter.filter(hierarchy(nodeIds, depths), predicate);
        assertEquals(hierarchy(expectedIds, expectedDepths).formatString(), actual.formatString());
    }

    private static int[] ints(int... values) {
        return values;
    }

    // The example from the assignment.
    @Test
    void filtersExampleFromTask() {
        assertFiltered(
            ints(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            ints(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2),
            id -> id % 3 != 0,
            ints(1, 2, 5, 8, 10, 11),
            ints(0, 1, 1, 0, 1, 2));
    }

    @Test
    void emptyHierarchyStaysEmpty() {
        assertFiltered(ints(), ints(), id -> true, ints(), ints());
    }

    @Test
    void acceptAllReturnsSameHierarchy() {
        int[] ids = ints(1, 2, 3, 4, 5, 6);
        int[] depths = ints(0, 1, 2, 1, 0, 1);
        assertFiltered(ids, depths, id -> true, ids, depths);
    }

    @Test
    void rejectAllReturnsEmpty() {
        assertFiltered(ints(1, 2, 3, 4), ints(0, 1, 0, 1), id -> false, ints(), ints());
    }

    @Test
    void singleNodeKept() {
        assertFiltered(ints(7), ints(0), id -> true, ints(7), ints(0));
    }

    @Test
    void singleNodeRejected() {
        assertFiltered(ints(7), ints(0), id -> false, ints(), ints());
    }

    @Test
    void rejectedRootRemovesWholeTreeButKeepsOtherTrees() {
        // 1(2(3)) 4(5) 6
        assertFiltered(
            ints(1, 2, 3, 4, 5, 6),
            ints(0, 1, 2, 0, 1, 0),
            id -> id != 1,
            ints(4, 5, 6),
            ints(0, 1, 0));
    }

    @Test
    void rejectedLastRootRemovesTrailingTree() {
        assertFiltered(
            ints(1, 2, 3, 4),
            ints(0, 1, 0, 1),
            id -> id != 3,
            ints(1, 2),
            ints(0, 1));
    }

    @Test
    void rejectedInnerNodeRemovesSubtreeAndNextSiblingIsKept() {
        // 1( 2(3,4), 5(6) )
        assertFiltered(
            ints(1, 2, 3, 4, 5, 6),
            ints(0, 1, 2, 2, 1, 2),
            id -> id != 2,
            ints(1, 5, 6),
            ints(0, 1, 2));
    }

    @Test
    void rejectedLeafRemovesOnlyThatLeaf() {
        assertFiltered(
            ints(1, 2, 3, 4),
            ints(0, 1, 2, 2),
            id -> id != 3,
            ints(1, 2, 4),
            ints(0, 1, 2));
    }

    @Test
    void passingNodeUnderRejectedAncestorIsExcluded() {
        // Only node 2 fails; its descendants 3 and 4 pass the predicate but must still be excluded.
        assertFiltered(
            ints(1, 2, 3, 4),
            ints(0, 1, 2, 3),
            id -> id != 2,
            ints(1),
            ints(0));
    }

    @Test
    void returnsToShallowerLevelAfterSkippingDeepSubtree() {
        // Skip from depth 1 down to depth 4, then jump straight back to depth 0 and depth 1.
        assertFiltered(
            ints(1, 2, 3, 4, 5, 6, 7),
            ints(0, 1, 2, 3, 4, 0, 1),
            id -> id != 2,
            ints(1, 6, 7),
            ints(0, 0, 1));
    }

    @Test
    void consecutiveRejectedSiblings() {
        assertFiltered(
            ints(1, 2, 3, 4, 5, 6),
            ints(0, 1, 2, 1, 2, 1),
            id -> id != 2 && id != 4,
            ints(1, 6),
            ints(0, 1));
    }

    @Test
    void flatForestBehavesLikePlainFilter() {
        assertFiltered(
            ints(1, 2, 3, 4, 5, 6),
            ints(0, 0, 0, 0, 0, 0),
            id -> id % 2 == 0,
            ints(2, 4, 6),
            ints(0, 0, 0));
    }

    @Test
    void predicateIsNotCalledForDescendantsOfRejectedNodes() {
        List<Integer> tested = new ArrayList<>();
        HierarchyFilter.filter(
            hierarchy(ints(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), ints(0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2)),
            id -> {
                tested.add(id);
                return id % 3 != 0;
            });
        // 4 (under 3) and 7 (under 6) are never tested; each other node is tested exactly once.
        assertEquals(List.of(1, 2, 3, 5, 6, 8, 9, 10, 11), tested);
    }

    @Test
    void inputIsNotModified() {
        int[] ids = ints(1, 2, 3);
        int[] depths = ints(0, 1, 0);
        HierarchyFilter.filter(hierarchy(ids, depths), id -> id != 2);
        assertArrayEquals(ints(1, 2, 3), ids);
        assertArrayEquals(ints(0, 1, 0), depths);
    }

    @Test
    void deepChainDoesNotOverflowStack() {
        int n = 100_000;
        int[] ids = new int[n];
        int[] depths = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            depths[i] = i;
        }
        Hierarchy result = HierarchyFilter.filter(hierarchy(ids, depths), id -> id != n / 2);
        assertEquals(n / 2, result.size());
        assertEquals(n / 2 - 1, result.depth(result.size() - 1));
    }

    @Test
    void nullArgumentsAreRejected() {
        Hierarchy h = hierarchy(ints(1), ints(0));
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(null, id -> true));
        assertThrows(NullPointerException.class, () -> HierarchyFilter.filter(h, null));
    }

    @Test
    void arrayBasedHierarchyRejectsMismatchedLengths() {
        assertThrows(IllegalArgumentException.class, () -> new ArrayBasedHierarchy(ints(1, 2), ints(0)));
    }

    // Compares against a straightforward reference implementation on random valid forests.
    @Test
    void matchesReferenceImplementationOnRandomForests() {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            int n = random.nextInt(40);
            int[] ids = new int[n];
            int[] depths = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = i;
                depths[i] = i == 0 ? 0 : random.nextInt(depths[i - 1] + 2);
            }
            long seed = random.nextLong();
            IntPredicate predicate = id -> new Random(seed + id).nextInt(4) != 0;

            Hierarchy expected = referenceFilter(ids, depths, predicate);
            Hierarchy actual = HierarchyFilter.filter(hierarchy(ids, depths), predicate);
            assertEquals(expected.formatString(), actual.formatString(),
                () -> "depths=" + Arrays.toString(depths));
        }
    }

    // Keeps a node iff its parent was kept and it passes; keptAtDepth[d] tracks the latest node seen at depth d.
    private static Hierarchy referenceFilter(int[] ids, int[] depths, IntPredicate predicate) {
        boolean[] keptAtDepth = new boolean[ids.length + 1];
        List<Integer> outIds = new ArrayList<>();
        List<Integer> outDepths = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            int d = depths[i];
            boolean parentKept = d == 0 || keptAtDepth[d - 1];
            keptAtDepth[d] = parentKept && predicate.test(ids[i]);
            if (keptAtDepth[d]) {
                outIds.add(ids[i]);
                outDepths.add(d);
            }
        }
        return hierarchy(
            outIds.stream().mapToInt(Integer::intValue).toArray(),
            outDepths.stream().mapToInt(Integer::intValue).toArray());
    }
}
