package hierarchy;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * A node is present in the filtered hierarchy iff its node ID passes the predicate and all of its ancestors pass it as well.
 */
public final class HierarchyFilter {
    private HierarchyFilter() {
    }

    /**
     * Single pass over the input in DFS order, O(n) time and O(n) extra space, no recursion.
     *
     * <p>A kept node has all of its ancestors kept, so its depth in the output equals its depth in the input.
     * When a node is rejected its whole subtree (the following run of nodes deeper than it) is skipped
     * without calling the predicate.
     *
     * @param hierarchy a hierarchy satisfying the depth invariants of {@link Hierarchy}; not validated
     * @param nodeIdPredicate called at most once per node, and never for descendants of a rejected node
     * @return a new, independent hierarchy
     */
    public static Hierarchy filter(Hierarchy hierarchy, IntPredicate nodeIdPredicate) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(nodeIdPredicate, "nodeIdPredicate");

        int size = hierarchy.size();
        int[] nodeIds = new int[size];
        int[] depths = new int[size];
        int count = 0;
        // Depth of the rejected node whose subtree we are inside; MAX_VALUE when not inside one.
        int skipDepth = Integer.MAX_VALUE;

        for (int i = 0; i < size; i++) {
            int depth = hierarchy.depth(i);
            if (depth > skipDepth) {
                continue;
            }
            skipDepth = Integer.MAX_VALUE;
            int nodeId = hierarchy.nodeId(i);
            if (nodeIdPredicate.test(nodeId)) {
                nodeIds[count] = nodeId;
                depths[count] = depth;
                count++;
            } else {
                skipDepth = depth;
            }
        }
        return new ArrayBasedHierarchy(Arrays.copyOf(nodeIds, count), Arrays.copyOf(depths, count));
    }
}
