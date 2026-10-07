/*
 * Everything that can be said about an ordered pair of vertices (s, t):
 * adjacent, reachable, walk, trail, path, length, geodesic, geodesic distance
 * and semipath. Enumerations are capped so they stay instant on big graphs.
 */
package graphtheory;

import java.util.Arrays;
import java.util.Vector;

/**
 * @author mk
 */
public class VertexPair {

    public static final int PATH_CAP = 300;
    public static final int TRAIL_CAP = 300;
    public static final int GEODESIC_CAP = 50;
    public static final long STEP_CAP = 250000L;
    private static final double EPS = 1e-9;

    public final GraphData d;
    public final int s, t;

    // results
    public boolean adjacentForward, adjacentBackward;
    public int forwardEdges, backwardEdges;
    public boolean reachable;
    public int hops = -1;                       // geodesic distance in edges, -1 = unreachable
    public double weightedDistance = GraphData.INF;
    public Vector<int[]> geodesics = new Vector<int[]>();
    public boolean geodesicsTruncated;
    public Vector<int[]> paths = new Vector<int[]>();
    public boolean pathsTruncated;
    public Vector<int[]> trails = new Vector<int[]>();
    public boolean trailsTruncated;
    public long[] walkCounts;                   // walkCounts[k-1] = number of walks of length k
    public int[] semipath;                      // vertex sequence, null if none
    public int[] exampleWalk;                   // a walk that repeats a vertex, null if none

    private long steps;

    public VertexPair(GraphData d, int s, int t) {
        this.d = d;
        this.s = s;
        this.t = t;
    }

    public void analyze() {
        adjacentForward = s != t && d.arc[s][t];
        adjacentBackward = s != t && d.arc[t][s];
        forwardEdges = d.mult[s][t];
        backwardEdges = d.mult[t][s];

        int[] hop = d.hopFrom(s);
        hops = hop[t];
        reachable = hops >= 0;
        weightedDistance = d.weightFrom(s)[t];

        findGeodesics();
        findPaths();
        findTrails();
        countWalks();
        findSemipath();
        findExampleWalk();
    }

    // ------------------------------------------------------------------
    // Geodesics: simple paths along "tight" arcs of the shortest-path DAG
    // ------------------------------------------------------------------
    private double[] distS;

    private void findGeodesics() {
        geodesics.clear();
        distS = d.distFrom(s);
        if (distS[t] >= GraphData.INF) {
            return;
        }
        steps = 0;
        int[] seq = new int[d.n + 1];
        boolean[] on = new boolean[d.n];
        seq[0] = s;
        on[s] = true;
        geoDfs(s, seq, 1, on);
    }

    private void geoDfs(int u, int[] seq, int len, boolean[] on) {
        if (geodesics.size() >= GEODESIC_CAP || ++steps > STEP_CAP) {
            geodesicsTruncated = true;
            return;
        }
        if (u == t) {
            geodesics.add(Arrays.copyOf(seq, len));
            return;
        }
        for (int v : d.out[u]) {
            if (on[v]) {
                continue;
            }
            if (Math.abs(distS[u] + d.arcLength(u, v) - distS[v]) > EPS || distS[v] > distS[t] + EPS) {
                continue;
            }
            on[v] = true;
            seq[len] = v;
            geoDfs(v, seq, len + 1, on);
            on[v] = false;
        }
    }

    // ------------------------------------------------------------------
    // Simple paths (no repeated vertex)
    // ------------------------------------------------------------------
    private void findPaths() {
        paths.clear();
        steps = 0;
        int[] seq = new int[d.n + 1];
        boolean[] on = new boolean[d.n];
        seq[0] = s;
        on[s] = true;
        pathDfs(s, seq, 1, on);
        java.util.Collections.sort(paths, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                return a.length - b.length;
            }
        });
    }

    private void pathDfs(int u, int[] seq, int len, boolean[] on) {
        if (paths.size() >= PATH_CAP || ++steps > STEP_CAP) {
            pathsTruncated = true;
            return;
        }
        if (u == t) {
            paths.add(Arrays.copyOf(seq, len));
            return;
        }
        for (int v : d.out[u]) {
            if (!on[v]) {
                on[v] = true;
                seq[len] = v;
                pathDfs(v, seq, len + 1, on);
                on[v] = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Trails (no repeated edge, vertices may repeat)
    // ------------------------------------------------------------------
    private void findTrails() {
        trails.clear();
        steps = 0;
        boolean[] used = new boolean[d.m];
        int[] seq = new int[d.m + 1];
        seq[0] = s;
        trailDfs(s, used, seq, 0);
        java.util.Collections.sort(trails, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) {
                return a.length - b.length;
            }
        });
    }

    private void trailDfs(int u, boolean[] used, int[] seq, int len) {
        if (trails.size() >= TRAIL_CAP || ++steps > STEP_CAP) {
            trailsTruncated = true;
            return;
        }
        if (u == t && len > 0) {
            trails.add(Arrays.copyOf(seq, len + 1));
        }
        for (int e = 0; e < d.m; e++) {
            if (used[e]) {
                continue;
            }
            int next;
            if (d.eu[e] == u) {
                next = d.ev[e];
            } else if (!d.directed && d.ev[e] == u) {
                next = d.eu[e];
            } else {
                continue;
            }
            used[e] = true;
            seq[len + 1] = next;
            trailDfs(next, used, seq, len + 1);
            used[e] = false;
            if (trailsTruncated) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Walks: (A^k)[s][t] counts walks of length k (parallel edges count separately)
    // ------------------------------------------------------------------
    private void countWalks() {
        int n = d.n;
        int K = Math.min(Math.max(n, 4), 8);
        walkCounts = new long[K];
        long[] vec = new long[n];
        vec[s] = 1;
        for (int k = 0; k < K; k++) {
            long[] next = new long[n];
            for (int u = 0; u < n; u++) {
                if (vec[u] == 0) {
                    continue;
                }
                for (int v = 0; v < n; v++) {
                    if (d.mult[u][v] > 0) {
                        next[v] += vec[u] * d.mult[u][v];
                    }
                }
            }
            vec = next;
            walkCounts[k] = vec[t];
        }
    }

    private void findExampleWalk() {
        exampleWalk = null;
        if (!reachable) {
            return;
        }
        if (s == t) {
            for (int x : d.out[s]) {
                if (d.arc[x][s]) {
                    exampleWalk = new int[]{s, x, s};
                    return;
                }
            }
            return;
        }
        int[] base = geodesics.isEmpty() ? null : geodesics.get(0);
        if (base == null || base.length < 2) {
            return;
        }
        // bounce along the first step: s, x, s, x, ...
        if (d.arc[base[1]][base[0]]) {
            int[] w = new int[base.length + 2];
            w[0] = base[0];
            w[1] = base[1];
            w[2] = base[0];
            System.arraycopy(base, 1, w, 3, base.length - 1);
            exampleWalk = w;
            return;
        }
        // otherwise bounce along any back-and-forth pair on the way
        for (int i = 0; i + 1 < base.length; i++) {
            if (d.arc[base[i + 1]][base[i]]) {
                int[] w = new int[base.length + 2];
                System.arraycopy(base, 0, w, 0, i + 2);
                w[i + 2] = base[i];
                w[i + 3] = base[i + 1];
                System.arraycopy(base, i + 2, w, i + 4, base.length - i - 2);
                exampleWalk = w;
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Semipath: a path in the underlying undirected graph
    // ------------------------------------------------------------------
    private void findSemipath() {
        semipath = null;
        int[] parent = new int[d.n];
        int[] dist = d.hopFromUndirected(s, parent);
        if (dist[t] < 0) {
            return;
        }
        int[] p = new int[dist[t] + 1];
        int cur = t;
        for (int i = p.length - 1; i >= 0; i--) {
            p[i] = cur;
            cur = parent[cur];
        }
        semipath = p;
    }

    // ------------------------------------------------------------------
    // Helpers for display
    // ------------------------------------------------------------------
    public String walkString(int[] w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            if (i > 0) {
                sb.append(d.sep());
            }
            sb.append(d.names[w[i]]);
        }
        return sb.toString();
    }

    public String semipathString() {
        if (semipath == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder(d.names[semipath[0]]);
        for (int i = 1; i < semipath.length; i++) {
            int a = semipath[i - 1], b = semipath[i];
            String arrow;
            if (!d.directed) {
                arrow = " \u2013 ";
            } else if (d.arc[a][b] && d.arc[b][a]) {
                arrow = " \u2194 ";
            } else if (d.arc[a][b]) {
                arrow = " \u2192 ";
            } else {
                arrow = " \u2190 ";
            }
            sb.append(arrow).append(d.names[b]);
        }
        return sb.toString();
    }

    private boolean semipathIsDirectedPath() {
        if (semipath == null) {
            return false;
        }
        for (int i = 1; i < semipath.length; i++) {
            if (!d.arc[semipath[i - 1]][semipath[i]]) {
                return false;
            }
        }
        return true;
    }

    /** Weight of a vertex sequence using the lightest parallel edge at each step. */
    public double weightOf(int[] p) {
        double w = 0;
        for (int i = 1; i < p.length; i++) {
            w += d.minW[p[i - 1]][p[i]];
        }
        return w;
    }

    /** Edge indices that lie on any geodesic (lightest parallel edge per step). */
    public java.util.Set<Integer> geodesicEdges() {
        java.util.Set<Integer> set = new java.util.HashSet<Integer>();
        for (int[] g : geodesics) {
            for (int i = 1; i < g.length; i++) {
                int a = g[i - 1], b = g[i];
                for (int e = 0; e < d.m; e++) {
                    boolean match = (d.eu[e] == a && d.ev[e] == b) || (!d.directed && d.eu[e] == b && d.ev[e] == a);
                    if (match && Math.abs(d.ew[e] - d.minW[a][b]) < EPS) {
                        set.add(e);
                        break;
                    }
                }
            }
        }
        return set;
    }

    // ------------------------------------------------------------------
    // HTML report for the Pair tab
    // ------------------------------------------------------------------
    private static String row(String name, String valueHtml, String hint) {
        return "<tr><td valign='top' width='34%'><b>" + name + "</b><br><font color='#9AA0AA' size='-2'>" + hint
                + "</font></td><td valign='top'>" + valueHtml + "</td></tr>";
    }

    private static String yes(String s) {
        return "<font color='#1E8E3E'><b>" + s + "</b></font>";
    }

    private static String no(String s) {
        return "<font color='#8A8F9A'>" + s + "</font>";
    }

    public String toHtml() {
        String S = Ui.esc(d.names[s]), T = Ui.esc(d.names[t]);
        StringBuilder h = new StringBuilder();
        h.append("<html><body style='font-family:Segoe UI,sans-serif;font-size:11px;color:#1F2430'>");
        h.append("<table width='100%' cellpadding='4' cellspacing='0' border='0'>");

        // Adjacent
        String adj;
        if (s == t) {
            adj = no("same vertex") + (d.loops[s] > 0 ? " \u2014 has " + d.loops[s] + " self-loop(s)" : "");
        } else if (!d.directed) {
            adj = d.arc[s][t] ? yes("yes") + (forwardEdges > 1 ? " \u2014 " + forwardEdges + " parallel edges" : "") : no("no");
        } else if (adjacentForward && adjacentBackward) {
            adj = yes("yes, both ways") + " (" + S + "\u2192" + T + " \u00D7" + forwardEdges + ", " + T + "\u2192" + S + " \u00D7" + backwardEdges + ")";
        } else if (adjacentForward) {
            adj = yes("yes") + " \u2014 " + S + "\u2192" + T + (forwardEdges > 1 ? " \u00D7" + forwardEdges : "") + " (not " + T + "\u2192" + S + ")";
        } else if (adjacentBackward) {
            adj = no("no") + " \u2014 only " + T + "\u2192" + S + " exists";
        } else {
            adj = no("no");
        }
        h.append(row("Adjacent", adj, "joined by an edge"));

        // Reachable
        String reach;
        if (reachable) {
            reach = yes("yes") + " \u2014 " + T + " can be reached from " + S;
        } else {
            reach = no("no") + " \u2014 no " + (d.directed ? "directed " : "") + "route from " + S + " to " + T;
        }
        h.append(row("Reachable", reach, "some path exists"));

        // Geodesic distance
        String dist = reachable ? "<b>" + hops + "</b> edge(s)" : no("\u221E");
        if (d.weighted) {
            dist += reachable ? "; weighted distance <b>" + Ui.fmt(weightedDistance) + "</b>" : "";
        }
        h.append(row("Geodesic distance", dist, "length of a shortest path"));

        // Geodesic
        String geo;
        if (geodesics.isEmpty()) {
            geo = no("none");
        } else {
            StringBuilder g = new StringBuilder();
            g.append(geodesics.size()).append(geodesicsTruncated ? "+" : "").append(" shortest path(s)")
                    .append(d.weighted ? " (by weight)" : "").append(":<br>");
            for (int i = 0; i < Math.min(4, geodesics.size()); i++) {
                g.append("&nbsp;&nbsp;<font color='#0F9D9D'><b>").append(Ui.esc(d.pathString(geodesics.get(i), geodesics.get(i).length)))
                        .append("</b></font><br>");
            }
            if (geodesics.size() > 4) {
                g.append("&nbsp;&nbsp;\u2026 and ").append(geodesics.size() - 4).append(" more<br>");
            }
            geo = g.toString();
        }
        h.append(row("Geodesic", geo, "a shortest path (drawn teal on the canvas)"));

        // Length
        String len;
        if (paths.isEmpty()) {
            len = no("n/a");
        } else {
            int shortest = paths.get(0).length - 1, longest = paths.get(paths.size() - 1).length - 1;
            len = "shortest path <b>" + shortest + "</b>, longest simple path <b>" + longest + "</b> edge(s)"
                    + (pathsTruncated ? " (partial search)" : "");
        }
        h.append(row("Length", len, "number of edges on a path"));

        // Walk
        StringBuilder w = new StringBuilder();
        w.append("walks of length 1..").append(walkCounts.length).append(": <b>");
        for (int k = 0; k < walkCounts.length; k++) {
            if (k > 0) {
                w.append(", ");
            }
            w.append(walkCounts[k]);
        }
        w.append("</b>");
        if (exampleWalk != null) {
            w.append("<br>repeats allowed, e.g. ").append(Ui.esc(walkString(exampleWalk)));
        }
        h.append(row("Walk", w.toString(), "vertices and edges may repeat"));

        // Trail
        String tr;
        if (trails.isEmpty()) {
            tr = no("none") + (s == t ? " (no closed trail through " + S + ")" : "");
        } else {
            int longestT = trails.get(trails.size() - 1).length - 1;
            tr = "<b>" + trails.size() + (trailsTruncated ? "+" : "") + "</b> trail(s); longest uses <b>" + longestT + "</b> edge(s)";
        }
        h.append(row("Trail", tr, "no edge repeats, vertices may"));

        // Path
        String pa = paths.isEmpty() ? no("none")
                : "<b>" + paths.size() + (pathsTruncated ? "+" : "") + "</b> simple path(s)";
        h.append(row("Path", pa, "no vertex repeats"));

        // Semipath
        String sp = semipath == null ? no("none") : "<b>" + Ui.esc(semipathString()) + "</b>";
        if (semipath != null && d.directed) {
            sp += semipathIsDirectedPath() ? "<br><font color='#1E8E3E'>also a directed path</font>"
                    : "<br><font color='#C25E00'>not a directed path (some steps go against the arrows)</font>";
        }
        h.append(row("Semipath", sp, "path that ignores edge direction"));
        h.append("</table>");

        // Listings
        if (!paths.isEmpty()) {
            h.append("<p><b>Simple paths</b> <font color='#9AA0AA'>(shortest first)</font><br>");
            int show = Math.min(paths.size(), 25);
            for (int i = 0; i < show; i++) {
                int[] p = paths.get(i);
                h.append("<font face='Monospaced'>").append(Ui.esc(d.pathString(p, p.length)))
                        .append("</font> <font color='#9AA0AA'>len ").append(p.length - 1);
                if (d.weighted) {
                    h.append(", weight ").append(Ui.fmt(weightOf(p)));
                }
                h.append("</font><br>");
            }
            if (paths.size() > show || pathsTruncated) {
                h.append("<font color='#9AA0AA'>\u2026 ").append(paths.size() - show).append(pathsTruncated ? "+" : "")
                        .append(" more not shown</font><br>");
            }
        }
        if (!trails.isEmpty()) {
            h.append("<p><b>Trails</b> <font color='#9AA0AA'>(shortest first)</font><br>");
            int show = Math.min(trails.size(), 15);
            for (int i = 0; i < show; i++) {
                int[] p = trails.get(i);
                h.append("<font face='Monospaced'>").append(Ui.esc(d.pathString(p, p.length)))
                        .append("</font> <font color='#9AA0AA'>len ").append(p.length - 1).append("</font><br>");
            }
            if (trails.size() > show || trailsTruncated) {
                h.append("<font color='#9AA0AA'>\u2026 ").append(trails.size() - show).append(trailsTruncated ? "+" : "")
                        .append(" more not shown</font><br>");
            }
        }
        h.append("</body></html>");
        return h.toString();
    }
}