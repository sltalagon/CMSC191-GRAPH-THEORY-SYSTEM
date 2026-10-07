/*
 * Matrices (adjacency / weight / distance) and the vertex-disjoint "container"
 * analysis used for the k-wide diameters D_k(G).
 */
package graphtheory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Vector;

/**
 * @author mk
 */
public class GraphProperties {

    /** Containers are exponential in the number of vertices, so they are limited. */
    public static final int CONTAINER_LIMIT = 8;
    public static final int AUTO_CONTAINER_LIMIT = 6;
    private static final int PATH_CAP = 1500;
    private static final int DETAIL_CONTAINERS_PER_PAIR = 5;

    // ------------------------------------------------------------------
    // Matrices
    // ------------------------------------------------------------------
    /** Number of edges between each pair (diagonal = number of loops). */
    public static int[][] adjacencyMatrix(GraphData d) {
        int[][] a = new int[d.n][];
        for (int i = 0; i < d.n; i++) {
            a[i] = d.mult[i].clone();
        }
        return a;
    }

    /** Lightest edge weight between each pair (infinity where there is no edge). */
    public static double[][] weightMatrix(GraphData d) {
        double[][] w = new double[d.n][];
        for (int i = 0; i < d.n; i++) {
            w[i] = d.minW[i].clone();
        }
        return w;
    }

    /** Shortest distance between each pair in the active metric (edges, or weights if weighted). */
    public static double[][] distanceMatrix(GraphData d) {
        double[][] m = new double[d.n][];
        for (int i = 0; i < d.n; i++) {
            m[i] = d.distFrom(i);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Containers / wide diameters
    // ------------------------------------------------------------------
    public static class ContainerResult {

        public String[] columns = new String[0];
        public Object[][] rows = new Object[0][0];
        public String diameterLine = "";
        public String detail = "";
        public boolean truncated;       // some pair had more paths than the cap
        public int maxWidth;
    }

    /**
     * For every pair, builds containers (sets of internally vertex-disjoint paths) and
     * derives d_k(u,v), then D_k(G) = max over pairs. Paths follow arc direction.
     * Containers are built greedily from each path, as in the original project.
     */
    public static ContainerResult computeContainers(GraphData d) {
        ContainerResult res = new ContainerResult();
        int n = d.n;
        Vector<String> pairNames = new Vector<String>();
        Vector<int[]> rows = new Vector<int[]>();
        int[] D = new int[n + 2];
        Arrays.fill(D, -1);
        StringBuilder detail = new StringBuilder();

        for (int a = 0; a < n; a++) {
            for (int b = d.directed ? 0 : a + 1; b < n; b++) {
                if (a == b) {
                    continue;
                }
                String pairName = "(" + d.names[a] + (d.directed ? "\u2192" : ",") + d.names[b] + ")";
                Vector<int[]> paths = new Vector<int[]>();
                boolean[] on = new boolean[n];
                int[] seq = new int[n];
                seq[0] = a;
                on[a] = true;
                boolean[] trunc = {false};
                enumerate(d, a, b, seq, 1, on, paths, trunc);
                if (trunc[0]) {
                    res.truncated = true;
                }

                // internal-vertex sets for fast disjointness tests
                BitSet[] inner = new BitSet[paths.size()];
                for (int i = 0; i < paths.size(); i++) {
                    inner[i] = new BitSet(n);
                    int[] p = paths.get(i);
                    for (int k = 1; k < p.length - 1; k++) {
                        inner[i].set(p[k]);
                    }
                }
                Vector<BitSet> containers = new Vector<BitSet>();
                for (int i = 0; i < paths.size(); i++) {
                    BitSet members = new BitSet(paths.size());
                    members.set(i);
                    BitSet used = (BitSet) inner[i].clone();
                    for (int j = 0; j < paths.size(); j++) {
                        if (j != i && !used.intersects(inner[j])) {
                            members.set(j);
                            used.or(inner[j]);
                        }
                    }
                    boolean contained = false;
                    for (BitSet c : containers) {
                        BitSet x = (BitSet) members.clone();
                        x.andNot(c);
                        if (x.isEmpty()) {
                            contained = true;
                            break;
                        }
                    }
                    if (!contained) {
                        containers.add(members);
                    }
                }

                // path lengths per container, longest first
                Vector<int[]> lens = new Vector<int[]>();
                Vector<BitSet> kept = new Vector<BitSet>();
                int pairMaxWidth = 0;
                for (BitSet c : containers) {
                    int[] l = new int[c.cardinality()];
                    int k = 0;
                    for (int i = c.nextSetBit(0); i >= 0; i = c.nextSetBit(i + 1)) {
                        l[k++] = paths.get(i).length - 1;
                    }
                    Arrays.sort(l);                     // ascending: l[k-1] = k-th shortest
                    lens.add(l);
                    kept.add(c);
                    pairMaxWidth = Math.max(pairMaxWidth, l.length);
                }

                int[] row = new int[D.length];
                Arrays.fill(row, -1);
                for (int k = 1; k <= pairMaxWidth; k++) {
                    int best = Integer.MAX_VALUE;
                    for (int[] l : lens) {
                        if (l.length >= k) {
                            best = Math.min(best, l[k - 1]);
                        }
                    }
                    row[k] = best;
                    D[k] = Math.max(D[k], best);
                }
                pairNames.add(pairName);
                rows.add(row);
                res.maxWidth = Math.max(res.maxWidth, pairMaxWidth);

                // text detail (a handful of widest containers per pair)
                detail.append(pairName).append("  \u2014  ").append(paths.size()).append(" path(s)")
                        .append(trunc[0] ? "+" : "").append(", widest container ").append(pairMaxWidth).append("\n");
                Integer[] orderIdx = new Integer[containers.size()];
                for (int i = 0; i < orderIdx.length; i++) {
                    orderIdx[i] = i;
                }
                final Vector<int[]> flens = lens;
                Arrays.sort(orderIdx, new Comparator<Integer>() {
                    public int compare(Integer x, Integer y) {
                        int c = flens.get(y).length - flens.get(x).length;
                        if (c != 0) {
                            return c;
                        }
                        return flens.get(x)[flens.get(x).length - 1] - flens.get(y)[flens.get(y).length - 1];
                    }
                });
                int shown = Math.min(orderIdx.length, DETAIL_CONTAINERS_PER_PAIR);
                for (int q = 0; q < shown; q++) {
                    BitSet c = containers.get(orderIdx[q]);
                    int[] l = lens.get(orderIdx[q]);
                    detail.append("  Container ").append(q + 1).append("  (width ").append(l.length)
                            .append(", longest path ").append(l[l.length - 1]).append(")\n");
                    int pi = 1;
                    Vector<int[]> members = new Vector<int[]>();
                    for (int i = c.nextSetBit(0); i >= 0; i = c.nextSetBit(i + 1)) {
                        members.add(paths.get(i));
                    }
                    Collections.sort(members, new Comparator<int[]>() {
                        public int compare(int[] x, int[] y) {
                            return y.length - x.length;
                        }
                    });
                    for (int[] p : members) {
                        detail.append("    P").append(pi++).append(": ").append(d.pathString(p, p.length))
                                .append("  (length ").append(p.length - 1).append(")\n");
                    }
                }
                if (orderIdx.length > shown) {
                    detail.append("  \u2026 ").append(orderIdx.length - shown).append(" more container(s)\n");
                }
                detail.append("\n");
            }
        }

        int maxK = 0;
        for (int k = 1; k < D.length; k++) {
            if (D[k] != -1) {
                maxK = k;
            }
        }
        res.columns = new String[maxK + 1];
        res.columns[0] = "Pair";
        for (int k = 1; k <= maxK; k++) {
            res.columns[k] = k + "-wide";
        }
        res.rows = new Object[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            Object[] line = new Object[maxK + 1];
            line[0] = pairNames.get(r);
            for (int k = 1; k <= maxK; k++) {
                line[k] = rows.get(r)[k] == -1 ? "-" : String.valueOf(rows.get(r)[k]);
            }
            res.rows[r] = line;
        }
        StringBuilder dl = new StringBuilder();
        for (int k = 1; k <= maxK; k++) {
            dl.append("D").append(k).append("(G) = ").append(D[k]).append("     ");
        }
        res.diameterLine = dl.toString().trim();
        res.detail = detail.toString();
        return res;
    }

    private static void enumerate(GraphData d, int u, int target, int[] seq, int len, boolean[] on,
            Vector<int[]> paths, boolean[] trunc) {
        if (paths.size() >= PATH_CAP) {
            trunc[0] = true;
            return;
        }
        if (u == target) {
            paths.add(Arrays.copyOf(seq, len));
            return;
        }
        for (int v : d.out[u]) {
            if (!on[v]) {
                on[v] = true;
                seq[len] = v;
                enumerate(d, v, target, seq, len + 1, on, paths, trunc);
                on[v] = false;
                if (trunc[0]) {
                    return;
                }
            }
        }
    }
}