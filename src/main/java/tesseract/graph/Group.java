package tesseract.graph;

import tesseract.graph.traverse.BFDivider;
import tesseract.util.Dir;
import tesseract.util.Pos;

import java.util.*;
import java.util.function.Consumer;

/**
 * Group provides the functionality of a set of adjacent nodes that may or may not be linked.
 * @apiNote default parameters are nonnull, methods return nonnull.
 */
public class Group<C extends IConnectable, N extends IConnectable> implements INode, IGroup<C, N> {

    private HashMap<Pos, Connectivity.Cache<N>> nodes;
    private HashMap<Pos, UUID> connectors; // connectors pairing
    private HashMap<UUID, Grid<C>> grids;
    private BFDivider divider;

    // Prevent the creation of empty groups externally, a caller needs to use singleNode/singleConnector.
    private Group() {
        nodes = new HashMap<>();
        connectors = new HashMap<>();
        grids = new HashMap<>();
        divider = new BFDivider(this);
    }

    /**
     *
     * @param at
     * @param node
     * @return
     */
    public static <C extends IConnectable, N extends IConnectable> Group<C, N> singleNode(Pos at, Connectivity.Cache<N> node) {
        Group<C, N> group = new Group<>();
        group.addNode(at, node);
        return group;
    }

    /**
     *
     * @param at
     * @param connector
     * @return
     */
    public static <C extends IConnectable, N extends IConnectable> Group<C, N> singleConnector(Pos at, Connectivity.Cache<C> connector) {
        Group<C, N> group = new Group<>();
        UUID id = group.getNewId();

        group.connectors.put(at, id);
        group.grids.put(id, Grid.singleConnector(at, connector));

        return group;
    }

    @Override
    public boolean contains(Pos at) {
        Objects.requireNonNull(at);

        return nodes.containsKey(at) || connectors.containsKey(at);
    }

    @Override
    public boolean linked(Pos from, Dir towards, Pos to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);

        return contains(from) && contains(to);
    }

    @Override
    public boolean connects(Pos position, Dir towards) {
        return contains(position);
    }

    @Override
    public int countBlocks() {
        return nodes.size() + connectors.size();
    }

    @Override
    public LinkedHashSet<Pos> getBlocks() {
        LinkedHashSet<Pos> set = new LinkedHashSet<>();
        set.addAll(nodes.keySet());
        set.addAll(connectors.keySet());
        return set;
    }

    @Override
    public HashMap<Pos, N> getNodes() {
        HashMap<Pos, N> map = new HashMap<>();
        for (Map.Entry<Pos, Connectivity.Cache<N>> entry : nodes.entrySet()) {
            map.put(entry.getKey(), entry.getValue().value());
        }
        return map;
    }

    @Override
    public LinkedHashSet<IGrid<C>> getGrids() {
        return new LinkedHashSet<>(grids.values());
    }

    /**
     *
     * @param at
     * @param node
     */
    public void addNode(Pos at, Connectivity.Cache<N> node) {
        nodes.put(Objects.requireNonNull(at), Objects.requireNonNull(node));
    }

    /**
     *
     * @param at
     * @param connector
     */
    public void addConnector(Pos at, Connectivity.Cache<C> connector) {
        Objects.requireNonNull(connector);

        HashMap<UUID, Grid<C>> linkedGrids = new HashMap<>();
        UUID bestId = null;
        Grid<C> bestGrid = null;
        int bestCount = 0;

        int neighbors = 0;

        for (Dir direction : Dir.VALUES) {
            if (!connector.connects(direction)) {
                continue;
            }

            Pos offset = at.offset(direction);
            UUID id = connectors.get(offset);

            if (id == null) {
                neighbors += nodes.containsKey(offset) ? 1 : 0;
                continue;
            }

            neighbors += 1;

            Grid<C> grid = grids.get(id);

            if (grid.connects(offset, direction.invert())) {
                linkedGrids.put(id, grid);

                if (grid.countConnectors() > bestCount) {
                    bestCount = grid.countConnectors();
                    bestGrid = grid;
                    bestId = id;
                }
            }
        }

        if (neighbors == 0) {
            throw new IllegalStateException("Group::addConnector: Attempted to add a position that would not be touching the group!");
        }

        if (linkedGrids.isEmpty()) {
            // Single connector grid
            UUID id = getNewId();

            connectors.put(at, id);
            grids.put(id, Grid.singleConnector(at, connector));
            return;
        }

        if (bestGrid == null) {
            throw new IllegalStateException();
        }

        // Add to the best grid
        connectors.put(at, bestId);
        bestGrid.addConnector(at, connector);

        if (linkedGrids.size() == 1) {
            // No other grids to merge with
            return;
        }

        for (Map.Entry<UUID, Grid<C>> entry : linkedGrids.entrySet()) {
            UUID id = entry.getKey();
            Grid<C> grid = entry.getValue();

            if (id.equals(bestId)) {
                continue;
            }

            bestGrid.mergeWith(at, grid);
            for (Pos item : grid.getConnectors().keySet()) {
                connectors.put(item, bestId);
            }
            grids.remove(id);
        }
    }

    /**
     * Removes an entry from the Group, potentially splitting it if needed. By calling this function, the caller asserts
     * that this group contains the specified position; the function may misbehave if the group does not actually contain
     * the specified position.
     *
     * @param posToRemove The position of the entry to remove.
     * @param split       A consumer for the resulting fresh graphs from the split operation.
     * @return The removed entry, guaranteed to not be null.
     */
    public Entry<C, N> remove(Pos posToRemove, Consumer<Group<C, N>> split) {
        // The contains() check can be skipped here, because Graph will only call remove() if it knows that the group contains the entry.
        // For now, it is retained for completeness and debugging purposes.
        if (!contains(posToRemove)) {
            throw new IllegalArgumentException("Tried to call Group::remove with a position that does not exist within the group.");
        }

        // If removing the entry would not cause a group split, then it is safe to remove the entry directly.
        if (isExternal(posToRemove)) {
            Connectivity.Cache<N> node = nodes.remove(posToRemove);
            UUID pairing = connectors.remove(posToRemove);

            if (node != null) {
                return Entry.node(node.value());
            }

            Grid<C> grid = grids.get(Objects.requireNonNull(pairing));

            // No check is needed here, because the caller already asserts that the Group contains the specified position.
            // Thus, if this is not a node, then it is guaranteed to be a connector.
            C removed = grid.remove(
                posToRemove,
                newGrid -> {
                    UUID newId = getNewId();
                    grids.put(newId, newGrid);

                    for (Pos pos : newGrid.getConnectors().keySet()) {
                        connectors.put(pos, newId);
                    }
                }
            );

            // Avoid leaving empty grids within the grid list.
            if (grid.countConnectors() == 0) {
                grids.remove(pairing);
            }

            return Entry.connector(removed);
        }

        // If none of the fast routes work, we need to due a full group-traversal to figure out how the graph will be split.
        // The algorithm works by "coloring" each fragment of the group based on what it is connected to, and then from this,
        // splitting each colored portion into its own separate group.

        // For optimization purposes, the largest colored fragment remains resident within its original group.
        // Note: we don't remove the node yet, but instead just tell the Searcher to exclude it.
        // This is so that we can handle the grid splits ourselves at the end.
        ArrayList<LinkedHashSet<Pos>> colored = new ArrayList<>();

        int bestColor = divider.divide(
            removed -> removed.add(posToRemove),
            roots -> {
                for (Dir direction : Dir.VALUES) {
                    Pos side = posToRemove.offset(direction);

                    if (linked(posToRemove, direction, side)) {
                        roots.add(side);
                    }
                }
            },
            colored::add
        );

        LinkedHashSet<Grid<C>> splitGrids = null;
        HashSet<Pos> excluded = new HashSet<>();

        Entry<C, N> result;

        UUID centerGridId = connectors.get(posToRemove);
        if (centerGridId != null) {
            Grid<C> centerGrid = grids.remove(centerGridId);
            splitGrids = new LinkedHashSet<>();

            for (Pos move : centerGrid.getConnectors().keySet()) {
                connectors.remove(move);
                excluded.add(move);
            }

            result = Entry.connector(centerGrid.remove(posToRemove, splitGrids::add));
            splitGrids.add(centerGrid);
        } else {
            result = Entry.node(nodes.remove(posToRemove).value());
        }

        for (int i = 0; i < colored.size(); i++) {
            LinkedHashSet<Pos> found = colored.get(i);
            Group<C, N> newGroup;

            if (i != bestColor) {
                newGroup = new Group<>();

                for (Pos reached : found) {
                    if (newGroup.connectors.containsKey(reached) || (excluded.contains(reached))) {
                        continue;
                    }

                    UUID gridId = connectors.get(reached);

                    // Just a node then, simply add it to the new group.
                    // The maps are mutated directly here in order to retain the cached connectivity.
                    if (gridId == null) {
                        newGroup.nodes.put(reached, Objects.requireNonNull(nodes.remove(reached)));
                        continue;
                    }

                    Grid<C> grid = grids.get(gridId);
                    if (grid.contains(posToRemove)) {
                        // This should be unreachable
                        throw new IllegalStateException("Searchable grid contains the removed position, the grid should have been removed already?!?");
                    }

                    // Move grid to new group
                    grids.remove(gridId);
                    newGroup.grids.put(gridId, grid);

                    for (Pos moved : grid.getConnectors().keySet()) {
                        connectors.remove(moved);
                        newGroup.connectors.put(moved, gridId);
                    };
                }
            } else {
                newGroup = this;
            }

            // Add the fragments of the center grid, if present, to each group
            if (splitGrids != null) {
                Iterator<Grid<C>> iterator = splitGrids.iterator();

                while (iterator.hasNext()) {
                    Grid<C> grid = iterator.next();
                    Pos sample = grid.sampleConnector();

                    if (found.contains(sample)) {
                        UUID newId = newGroup.getNewId();

                        newGroup.addGrid(newId, grid);
                        iterator.remove();
                    }
                }
            }

            if (i != bestColor) {
                split.accept(newGroup);
            }
        }

        return Objects.requireNonNull(result);
    }

    /**
     *
     * @param id
     * @param grid
     */
    private void addGrid(UUID id, Grid<C> grid) {
        grids.put(id, grid);
        for (Pos moved : grid.getConnectors().keySet()) {
            connectors.put(moved, id);
        }
    }

    /**
     * Tests if a particular position is only connected to the group on a single side, or is the only entry in the group.
     *
     * @param pos The position to test
     * @return Whether the position only has a single neighbor in the group, or is the only entry in the group.
     */
    private boolean isExternal(Pos pos) {
        // If the group contains less than 2 blocks, neighbors cannot exist.
        if (countBlocks() <= 1) {
            return true;
        }

        int neighbors = 0;
        for (Dir direction : Dir.VALUES) {
            Pos face = pos.offset(direction);

            if (contains(face)) {
                neighbors += 1;
            }
        }

        return neighbors <= 1;
    }

    // Graph controlled interface

    /**
     *
     * @param other
     * @param at
     */
    void mergeWith(Group<C, N> other, Pos at) {
        nodes.putAll(other.nodes);
        connectors.putAll(other.connectors);

        for (UUID id : other.grids.keySet()) {
            if (grids.containsKey(id)) {
                throw new IllegalStateException("Duplicate grid UUIDs when attempting to merge groups, this should never happen!");
            }
        };

        UUID pairing = connectors.get(at);

        if (pairing != null) {
            Grid<C> currentGrid = grids.get(pairing);

            for (Dir direction : Dir.VALUES) {
                Pos offset = at.offset(direction);

                if (!currentGrid.connects(at, direction)) {
                    continue;
                }

                UUID id = other.connectors.get(offset);

                if (id == null) {
                    continue;
                }

                Grid<C> grid = other.grids.remove(id);

                if (grid == null) {
                    // Already removed.
                    continue;
                }

                if (grid.connects(offset, direction.invert())) {
                    currentGrid.mergeWith(at, grid);
                    for (Pos position : grid.getConnectors().keySet()) {
                        connectors.put(position, pairing);
                    }
                }
            }
        }

        grids.putAll(other.grids);
    }

    /**
     * @return Pseudo randomly generates an immutable universally unique identifier.
     */
    private UUID getNewId() {
        UUID uuid = UUID.randomUUID();
        while (grids.containsKey(uuid)) {
            // Should never be called, but whatever.
            uuid = UUID.randomUUID();
        }

        return uuid;
    }
}
