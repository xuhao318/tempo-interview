package hierarchy;

import java.util.Objects;

/** A {@link Hierarchy} backed by two parallel arrays. The arrays are not copied. */
public class ArrayBasedHierarchy implements Hierarchy {
    private final int[] nodeIds;
    private final int[] depths;

    public ArrayBasedHierarchy(int[] nodeIds, int[] depths) {
        Objects.requireNonNull(nodeIds, "nodeIds");
        Objects.requireNonNull(depths, "depths");
        if (nodeIds.length != depths.length) {
            throw new IllegalArgumentException(
                "nodeIds and depths must have the same length: " + nodeIds.length + " != " + depths.length);
        }
        this.nodeIds = nodeIds;
        this.depths = depths;
    }

    @Override
    public int size() {
        return depths.length;
    }

    @Override
    public int nodeId(int index) {
        return nodeIds[index];
    }

    @Override
    public int depth(int index) {
        return depths[index];
    }
}
