package net.jimmykw.knowledgegraph.resolve.cleanup;

import io.vavr.Tuple2;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.collection.Set;
import lombok.experimental.UtilityClass;
import lombok.val;

/**
 * Groups node ids into candidate clusters (union-find). Exact edges always join; a full-text edge joins only while the result
 * stays within {@code maxSize}, so chains of similar names ("Windows 95" ~ "Windows 98" ~ ...) cannot snowball into one cluster.
 */
@UtilityClass
public class Clustering {

    /** Ids of one cluster in input order; {@code fuzzy} when a full-text edge helped form it. */
    public record Cluster(List<String> ids, boolean fuzzy) {
    }

    private record State(Map<String, String> parent, Map<String, Integer> size, Set<String> fuzzyRoots) {
    }

    /** Clusters of two or more ids, ordered by their first id's input position. */
    public static List<Cluster> cluster(List<String> ids, List<Tuple2<String, String>> exactEdges, List<Tuple2<String, String>> fuzzyEdges,
                                        int maxSize) {
        val initial = new State(ids.toMap(id -> id, id -> id), ids.toMap(id -> id, id -> 1), HashSet.empty());
        val known = ids.toSet();
        val exact = exactEdges.filter(edge -> known.contains(edge._1) && known.contains(edge._2))
                .foldLeft(initial, (state, edge) -> union(state, edge._1, edge._2, false, Integer.MAX_VALUE));
        val joined = fuzzyEdges.filter(edge -> known.contains(edge._1) && known.contains(edge._2))
                .foldLeft(exact, (state, edge) -> union(state, edge._1, edge._2, true, maxSize));
        val position = ids.zipWithIndex().toMap(pair -> pair._1, pair -> pair._2);
        return ids.groupBy(id -> find(joined, id))
                .filter(entry -> entry._2.size() >= 2)
                .map(entry -> new Cluster(entry._2.sortBy(id -> position.getOrElse(id, 0)), joined.fuzzyRoots().contains(entry._1)))
                .toList()
                .sortBy(cluster -> position.getOrElse(cluster.ids().head(), 0));
    }

    private static State union(State state, String left, String right, boolean fuzzy, int maxSize) {
        val leftRoot = find(state, left);
        val rightRoot = find(state, right);
        val combined = state.size().getOrElse(leftRoot, 1) + state.size().getOrElse(rightRoot, 1);
        if (leftRoot.equals(rightRoot) || combined > maxSize) {
            return state;
        }
        val wasFuzzy = fuzzy || state.fuzzyRoots().contains(leftRoot) || state.fuzzyRoots().contains(rightRoot);
        return new State(state.parent().put(rightRoot, leftRoot), state.size().put(leftRoot, combined).remove(rightRoot),
                wasFuzzy ? state.fuzzyRoots().remove(rightRoot).add(leftRoot) : state.fuzzyRoots().remove(rightRoot));
    }

    private static String find(State state, String id) {
        val parent = state.parent().getOrElse(id, id);
        return parent.equals(id) ? id : find(state, parent);
    }

    /** Edges that chain every id sharing a key (first id to each of the others). */
    public static List<Tuple2<String, String>> edgesByKey(Map<String, List<String>> idsByKey) {
        return idsByKey.values().toList().flatMap(group -> group.tail().map(other -> new Tuple2<>(group.head(), other)));
    }

    static <K, V> Map<K, List<V>> invert(List<Tuple2<V, List<K>>> keysById) {
        return keysById.foldLeft(HashMap.<K, List<V>>empty(), (acc, pair) -> pair._2.distinct()
                .foldLeft(acc, (inner, key) -> inner.put(key, inner.getOrElse(key, List.empty()).append(pair._1))));
    }
}
