package graphtheory;

import java.util.Arrays;
import java.util.Vector;

/**
 * Relations between two graphs: complement, isomorphism and subgraph tests.
 * Structural tests compare edge multiplicities (parallel edges and loops count)
 * and ignore weights. The backtracking searches have a step budget so they can
 * never hang the UI; an exhausted budget is reported as "undetermined".
 */
public final class GraphCompare {

    private GraphCompare() {
    }

    public static final long DEFAULT_BUDGET = 2000000L;

    /** Result of a mapping search. */
    public static final class Match {

        public int[] map;           // map[i] = vertex of the second graph for vertex i of the first
        public boolean exhausted;   // search stopped because the budget ran out
        public String note;         // why the test does not apply (e.g. mixed directed/undirected)
    }

    /** Complement of a graph as a new snapshot (simple, same orientation, weights 1). */
    public static GraphData complement(GraphData d) {
        Vector<int[]> es = new Vector<int[]>();
        for (int i = 0; i < d.n; i++) {
            for (int j = d.directed ? 0 : i + 1; j < d.n; j++) {
                if (i != j && !d.arc[i][j]) {
                    es.add(new int[]{i, j});
                }
            }
        }
        int[] eu = new int[es.size()], ev = new int[es.size()];
        double[] ew = new double[es.size()];
        for (int k = 0; k < es.size(); k++) {
            eu[k] = es.get(k)[0];
            ev[k] = es.get(k)[1];
            ew[k] = 1.0;
        }
        return new GraphData(d.names, d.directed, false, eu, ev, ew);
    }

    // ------------------------------------------------------------------
    // Same-label relations
    // ------------------------------------------------------------------
    /** True if every vertex/edge of {@code sub} is in {@code sup} (matched by vertex name). */
    public static boolean labelSubgraph(GraphData sub, GraphData sup, boolean induced) {
        if (sub.directed != sup.directed) {
            return false;
        }
        int[] map = new int[sub.n];
        boolean[] used = new boolean[sup.n];
        for (int i = 0; i < sub.n; i++) {
            map[i] = -1;
            for (int j = 0; j < sup.n; j++) {
                if (sup.names[j].equals(sub.names[i])) {
                    map[i] = j;
                }
            }
            if (map[i] < 0) {
                return false;
            }
            used[map[i]] = true;
        }
        for (int i = 0; i < sub.n; i++) {
            for (int j = 0; j < sub.n; j++) {
                if (sub.mult[i][j] > sup.mult[map[i]][map[j]]) {
                    return false;
                }
                if (induced && sub.mult[i][j] != sup.mult[map[i]][map[j]]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True if both graphs have the same vertex names and exactly the same adjacency. */
    public static boolean sameLabelledGraph(GraphData a, GraphData b) {
        return a.n == b.n && labelSubgraph(a, b, true);
    }

    // ------------------------------------------------------------------
    // Isomorphism / embedding (backtracking)
    // ------------------------------------------------------------------
    public static Match isomorphism(GraphData a, GraphData b) {
        return isomorphism(a, b, DEFAULT_BUDGET);
    }

    public static Match isomorphism(GraphData a, GraphData b, long budget) {
        Match r = new Match();
        if (a.directed != b.directed) {
            r.note = "one graph is directed and the other is not";
            return r;
        }
        if (a.n != b.n || a.m != b.m) {
            return r;
        }
        int[] sa = signature(a), sb = signature(b);
        Arrays.sort(sa);
        Arrays.sort(sb);
        if (!Arrays.equals(sa, sb)) {
            return r;
        }
        search(a, b, false, r, budget);
        return r;
    }

    /** Looks for a copy of {@code small} inside {@code big} (not necessarily induced), up to renaming. */
    public static Match embedding(GraphData small, GraphData big) {
        return embedding(small, big, DEFAULT_BUDGET);
    }

    public static Match embedding(GraphData small, GraphData big, long budget) {
        Match r = new Match();
        if (small.directed != big.directed) {
            r.note = "one graph is directed and the other is not";
            return r;
        }
        if (small.n > big.n || small.m > big.m) {
            return r;
        }
        search(small, big, true, r, budget);
        return r;
    }

    private static int[] signature(GraphData d) {
        int[] s = new int[d.n];
        for (int i = 0; i < d.n; i++) {
            s[i] = (d.outdeg[i] * 1000 + d.indeg[i]) * 100 + Math.min(99, d.loops[i]);
        }
        return s;
    }

    private static void search(GraphData a, GraphData b, boolean embed, Match r, long budget) {
        // order a's vertices: most constrained (highest degree) first
        Integer[] ord = new Integer[a.n];
        for (int i = 0; i < a.n; i++) {
            ord[i] = i;
        }
        final GraphData fa = a;
        java.util.Arrays.sort(ord, new java.util.Comparator<Integer>() {
            public int compare(Integer x, Integer y) {
                return fa.degree[y] - fa.degree[x];
            }
        });
        int[] order = new int[a.n];
        for (int i = 0; i < a.n; i++) {
            order[i] = ord[i];
        }
        int[] map = new int[a.n];
        Arrays.fill(map, -1);
        boolean[] used = new boolean[b.n];
        long[] steps = {0};
        if (extend(a, b, embed, order, 0, map, used, steps, budget)) {
            r.map = map.clone();
        } else if (steps[0] > budget) {
            r.exhausted = true;
        }
    }

    private static boolean extend(GraphData a, GraphData b, boolean embed, int[] order, int k,
            int[] map, boolean[] used, long[] steps, long budget) {
        if (k == order.length) {
            return true;
        }
        if (++steps[0] > budget) {
            return false;
        }
        int u = order[k];
        for (int c = 0; c < b.n; c++) {
            if (used[c]) {
                continue;
            }
            if (embed) {
                if (b.outdeg[c] < a.outdeg[u] || b.indeg[c] < a.indeg[u] || b.mult[c][c] < a.mult[u][u]) {
                    continue;
                }
            } else {
                if (b.outdeg[c] != a.outdeg[u] || b.indeg[c] != a.indeg[u] || b.mult[c][c] != a.mult[u][u]) {
                    continue;
                }
            }
            boolean ok = true;
            for (int j = 0; j < k && ok; j++) {
                int w = order[j], cw = map[w];
                if (embed) {
                    ok = a.mult[u][w] <= b.mult[c][cw] && a.mult[w][u] <= b.mult[cw][c];
                } else {
                    ok = a.mult[u][w] == b.mult[c][cw] && a.mult[w][u] == b.mult[cw][c];
                }
            }
            if (!ok) {
                continue;
            }
            map[u] = c;
            used[c] = true;
            if (extend(a, b, embed, order, k + 1, map, used, steps, budget)) {
                return true;
            }
            used[c] = false;
            map[u] = -1;
            if (steps[0] > budget) {
                return false;
            }
        }
        return false;
    }

    public static String mappingString(GraphData a, GraphData b, int[] map) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < map.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a.names[i]).append("\u2192").append(b.names[map[i]]);
        }
        return sb.toString();
    }
}