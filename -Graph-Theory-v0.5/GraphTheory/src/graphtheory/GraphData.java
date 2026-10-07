package graphtheory;

import java.util.Vector;

/**
 * Immutable, index-based snapshot of a {@link Graph}. All analysis code works on
 * this (never on the live, editable graph), which makes it safe to run on a
 * background thread.
 *
 * Conventions:
 * <ul>
 * <li>{@code mult[u][v]}: number of edges u -> v (directed) or joining u and v
 * (undirected, symmetric). The diagonal holds the number of self-loops.</li>
 * <li>{@code arc[u][v]}: u -> v adjacency, no loops (symmetric if undirected).</li>
 * <li>{@code uadj}/{@code ucount}: the same, but ignoring edge direction.</li>
 * <li>degree counts a self-loop twice (in a digraph it adds 1 to in- and out-degree).</li>
 * </ul>
 */
public final class GraphData {

    public static final double INF = Double.POSITIVE_INFINITY;

    public final int n, m;
    public final String[] names;
    public final boolean directed, weighted;
    public final int[] eu, ev;
    public final double[] ew;

    public final int[][] mult, ucount;
    public final boolean[][] arc, uadj;
    public final int[] loops, indeg, outdeg, degree;
    public final double[][] minW;
    public final int[][] out;       // out-neighbour lists over arc
    public final int[][] nbr;       // neighbour lists over uadj
    public final int totalLoops;

    public GraphData(String[] names, boolean directed, boolean weighted, int[] eu, int[] ev, double[] ew) {
        this.n = names.length;
        this.m = eu.length;
        this.names = names;
        this.directed = directed;
        this.weighted = weighted;
        this.eu = eu;
        this.ev = ev;
        this.ew = ew;

        mult = new int[n][n];
        ucount = new int[n][n];
        arc = new boolean[n][n];
        uadj = new boolean[n][n];
        loops = new int[n];
        indeg = new int[n];
        outdeg = new int[n];
        degree = new int[n];
        minW = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                minW[i][j] = INF;
            }
        }
        int loopSum = 0;
        for (int e = 0; e < m; e++) {
            int u = eu[e], v = ev[e];
            if (u == v) {
                mult[u][u]++;
                ucount[u][u]++;
                loops[u]++;
                loopSum++;
                outdeg[u]++;
                indeg[u]++;
                degree[u] += 2;
                continue;
            }
            mult[u][v]++;
            ucount[u][v]++;
            ucount[v][u]++;
            outdeg[u]++;
            indeg[v]++;
            degree[u]++;
            degree[v]++;
            minW[u][v] = Math.min(minW[u][v], ew[e]);
            if (!directed) {
                mult[v][u]++;
                minW[v][u] = Math.min(minW[v][u], ew[e]);
            }
        }
        totalLoops = loopSum;
        if (!directed) {
            for (int u = 0; u < n; u++) {      // in/out degree mirror the degree
                indeg[u] = degree[u];
                outdeg[u] = degree[u];
            }
        }
        out = new int[n][];
        nbr = new int[n][];
        for (int u = 0; u < n; u++) {
            Vector<Integer> o = new Vector<Integer>();
            Vector<Integer> b = new Vector<Integer>();
            for (int v = 0; v < n; v++) {
                if (u == v) {
                    continue;
                }
                arc[u][v] = mult[u][v] > 0;
                uadj[u][v] = ucount[u][v] > 0;
                if (arc[u][v]) {
                    o.add(v);
                }
                if (uadj[u][v]) {
                    b.add(v);
                }
            }
            out[u] = toArray(o);
            nbr[u] = toArray(b);
        }
    }

    public static GraphData of(Graph g) {
        int n = g.vertices.size();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = g.vertices.get(i).name;
        }
        java.util.Map<Vertex, Integer> idx = g.indexMap();
        int m = g.edges.size();
        int[] eu = new int[m], ev = new int[m];
        double[] ew = new double[m];
        for (int i = 0; i < m; i++) {
            Edge e = g.edges.get(i);
            eu[i] = idx.get(e.vertex1);
            ev[i] = idx.get(e.vertex2);
            ew[i] = e.weight;
        }
        return new GraphData(names, g.directed, g.weighted, eu, ev, ew);
    }

    private static int[] toArray(Vector<Integer> v) {
        int[] a = new int[v.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = v.get(i);
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Distances
    // ------------------------------------------------------------------
    /** BFS hop counts along arcs; -1 means unreachable. */
    public int[] hopFrom(int s) {
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        queue[tail++] = s;
        dist[s] = 0;
        while (head < tail) {
            int u = queue[head++];
            for (int v : out[u]) {
                if (dist[v] < 0) {
                    dist[v] = dist[u] + 1;
                    queue[tail++] = v;
                }
            }
        }
        return dist;
    }

    /** BFS hop counts ignoring edge direction (used for semipaths); -1 = unreachable. */
    public int[] hopFromUndirected(int s, int[] parent) {
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        java.util.Arrays.fill(parent, -1);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        queue[tail++] = s;
        dist[s] = 0;
        while (head < tail) {
            int u = queue[head++];
            for (int v : nbr[u]) {
                if (dist[v] < 0) {
                    dist[v] = dist[u] + 1;
                    parent[v] = u;
                    queue[tail++] = v;
                }
            }
        }
        return dist;
    }

    /** Dijkstra over the lightest parallel edge of each pair (weights are positive). */
    public double[] weightFrom(int s) {
        double[] dist = new double[n];
        java.util.Arrays.fill(dist, INF);
        boolean[] done = new boolean[n];
        dist[s] = 0;
        for (int it = 0; it < n; it++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && dist[i] < INF && (u == -1 || dist[i] < dist[u])) {
                    u = i;
                }
            }
            if (u == -1) {
                break;
            }
            done[u] = true;
            for (int v : out[u]) {
                double nd = dist[u] + minW[u][v];
                if (nd < dist[v]) {
                    dist[v] = nd;
                }
            }
        }
        return dist;
    }

    /** Distances in the active metric: weights when weighted, otherwise hop count. */
    public double[] distFrom(int s) {
        if (weighted) {
            return weightFrom(s);
        }
        int[] h = hopFrom(s);
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = h[i] < 0 ? INF : h[i];
        }
        return d;
    }

    /** Length of the arc u -> v in the active metric. */
    public double arcLength(int u, int v) {
        return weighted ? minW[u][v] : 1.0;
    }

    public String sep() {
        return directed ? " \u2192 " : " \u2013 ";
    }

    public String pathString(int[] p, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(sep());
            }
            sb.append(names[p[i]]);
        }
        return sb.toString();
    }

    public int distinctArcs() {
        int c = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && arc[i][j]) {
                    c++;
                }
            }
        }
        return directed ? c : c / 2;
    }

    public boolean isSimple() {
        if (totalLoops > 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && mult[i][j] > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    /** No loops and no two edges between the same pair, whatever their direction. */
    public boolean isUnderlyingSimple() {
        if (totalLoops > 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (ucount[i][j] > 1) {
                    return false;
                }
            }
        }
        return true;
    }
}