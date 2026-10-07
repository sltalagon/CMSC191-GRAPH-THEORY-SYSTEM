/*
 * Computes the graph-level and node-level properties of a graph snapshot and
 * packages them as ready-to-display rows for the Overview, Vertices, and Degree Distribution tabs.
 */
package graphtheory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.Vector;

public class GraphAnalyzer {

    // ---------------- display rows ----------------
    public static final int KIND_SECTION = 0, KIND_PROP = 1;
    public static final int NONE = 0, YES = 1, NO = 2, WARN = 3;

    /** One line of the Overview / Pair tables. */
    public static class Row {

        public final int kind;
        public final String name;
        public String value;
        public final String tip;
        public final int state;
        public final int color;     // section colour (RGB)

        Row(int kind, String name, String value, String tip, int state, int color) {
            this.kind = kind;
            this.name = name;
            this.value = value;
            this.tip = tip;
            this.state = state;
            this.color = color;
        }

        public static Row section(String title, int color, String tip) {
            return new Row(KIND_SECTION, title, "", tip, NONE, color);
        }

        public static Row prop(String name, String value, int state, String tip) {
            return new Row(KIND_PROP, name, value, tip, state, 0);
        }
    }

    // Exponential searches only run on small graphs
    public static final int HAMILTONIAN_LIMIT = 16;
    public static final int MAX_NON_HAM_LIMIT = 12;
    public static final int CONNECTIVITY_LIMIT = 40;

    // ---------------- results ----------------
    public GraphData d;
    public int order, size;
    public int components, strongComponents;
    public int vertexConnectivity = -1, edgeConnectivity = -1;
    public boolean connected, stronglyConnected;
    public boolean simple, multigraph, complete, empty, cycleGraph;
    public boolean bipartite, completeBipartite, star, tree, forest, cyclic;
    public boolean nonseparable;
    public boolean eulerian, semiEulerian;
    public boolean hamiltonianChecked, hamiltonian, traceable;
    public boolean maxNonHamChecked, maximalNonHamiltonian;
    public boolean selfComplementary;
    public Vector<Integer> cutVertices = new Vector<Integer>();
    public Vector<Integer> bridges = new Vector<Integer>();         // edge indices
    public Vector<Integer> isolated = new Vector<Integer>();
    public Vector<int[]> blocks = new Vector<int[]>();               // vertex indices per block
    public double density;

    // Degree distribution statistics
    public int[] degreeCounts = new int[0];
    public int[] inDegreeCounts = new int[0];
    public int[] outDegreeCounts = new int[0];
    public int minDegree, maxDegree;
    public double avgDegree;
    public int minInDegree, maxInDegree, minOutDegree, maxOutDegree;
    public double avgInDegree, avgOutDegree;

    public Vector<Row> overview = new Vector<Row>();
    public String[] nodeColumns = new String[0];
    public Class<?>[] nodeClasses = new Class<?>[0];
    public Object[][] nodeRows = new Object[0][0];

    // internals
    private int n;
    private int[] compOf;
    private Vector<Vector<Integer>> compList = new Vector<Vector<Integer>>();
    private int[] color2;                       // bipartition (0/1), -1 if not bipartite
    private int centre = -1;                    // centre of a star
    private String complementNote = "";
    private double[] degreeCentrality, closeness, betweenness;

    private static final double EPS = 1e-9;

    // ==================================================================
    /** Number of vertices in the graph last analysed (0 if none). */
    public int getN() {
        return d == null ? 0 : d.n;
    }

    public void analyze(GraphData data) {
        d = data;
        n = d.n;
        order = n;
        size = d.m;
        overview = new Vector<Row>();
        cutVertices = new Vector<Integer>();
        bridges = new Vector<Integer>();
        isolated = new Vector<Integer>();
        blocks = new Vector<int[]>();
        vertexConnectivity = -1;
        edgeConnectivity = -1;
        if (n == 0) {
            nodeRows = new Object[0][0];
            degreeCounts = new int[0];
            inDegreeCounts = new int[0];
            outDegreeCounts = new int[0];
            minDegree = maxDegree = 0;
            avgDegree = 0;
            return;
        }
        for (int v = 0; v < n; v++) {
            if (d.degree[v] == 0) {
                isolated.add(v);
            }
        }

        findComponents();
        findStrongComponents();
        findBlocks();
        simple = d.isSimple();
        multigraph = hasParallel();
        density = computeDensity();
        classifyFamilies();
        computeConnectivityNumbers();
        classifyTraversal();
        complementInfo();
        computeCentralities();
        computeDegreeDistribution();
        buildNodeTable();
        buildOverview();
    }

    // ==================================================================
    // Degree Distribution
    // ==================================================================
    private void computeDegreeDistribution() {
        if (n == 0) return;

        int maxD = 0, maxIn = 0, maxOut = 0;
        for (int v = 0; v < n; v++) {
            if (d.degree[v] > maxD) maxD = d.degree[v];
            if (d.indeg[v] > maxIn) maxIn = d.indeg[v];
            if (d.outdeg[v] > maxOut) maxOut = d.outdeg[v];
        }

        maxDegree = maxD;
        minDegree = Integer.MAX_VALUE;
        double sumDeg = 0;
        degreeCounts = new int[maxD + 1];

        for (int v = 0; v < n; v++) {
            int deg = d.degree[v];
            degreeCounts[deg]++;
            sumDeg += deg;
            if (deg < minDegree) minDegree = deg;
        }
        avgDegree = (double) sumDeg / n;

        if (d.directed) {
            inDegreeCounts = new int[maxIn + 1];
            outDegreeCounts = new int[maxOut + 1];
            minInDegree = Integer.MAX_VALUE;
            maxInDegree = maxIn;
            minOutDegree = Integer.MAX_VALUE;
            maxOutDegree = maxOut;
            double sumIn = 0, sumOut = 0;

            for (int v = 0; v < n; v++) {
                int inD = d.indeg[v];
                int outD = d.outdeg[v];
                inDegreeCounts[inD]++;
                outDegreeCounts[outD]++;
                sumIn += inD;
                sumOut += outD;
                if (inD < minInDegree) minInDegree = inD;
                if (outD < minOutDegree) minOutDegree = outD;
            }
            avgInDegree = sumIn / n;
            avgOutDegree = sumOut / n;
        }
    }

    // ==================================================================
    // Components
    // ==================================================================
    private void findComponents() {
        compOf = new int[n];
        Arrays.fill(compOf, -1);
        compList = new Vector<Vector<Integer>>();
        for (int s = 0; s < n; s++) {
            if (compOf[s] != -1) {
                continue;
            }
            Vector<Integer> comp = new Vector<Integer>();
            int id = compList.size();
            int[] stack = new int[n];
            int sp = 0;
            stack[sp++] = s;
            compOf[s] = id;
            while (sp > 0) {
                int u = stack[--sp];
                comp.add(u);
                for (int v : d.nbr[u]) {
                    if (compOf[v] == -1) {
                        compOf[v] = id;
                        stack[sp++] = v;
                    }
                }
            }
            java.util.Collections.sort(comp);
            compList.add(comp);
        }
        components = compList.size();
        connected = components == 1;
    }

    private boolean[] seen;
    private int[] finishOrder;
    private int finishCount;

    private void findStrongComponents() {
        if (!d.directed) {
            strongComponents = components;
            stronglyConnected = connected;
            return;
        }
        seen = new boolean[n];
        finishOrder = new int[n];
        finishCount = 0;
        for (int s = 0; s < n; s++) {
            if (!seen[s]) {
                dfsFinish(s);
            }
        }
        boolean[] assigned = new boolean[n];
        int count = 0;
        for (int i = n - 1; i >= 0; i--) {
            int s = finishOrder[i];
            if (assigned[s]) {
                continue;
            }
            count++;
            int[] stack = new int[n];
            int sp = 0;
            stack[sp++] = s;
            assigned[s] = true;
            while (sp > 0) {
                int u = stack[--sp];
                for (int w = 0; w < n; w++) {       // reverse arcs: w -> u
                    if (!assigned[w] && d.arc[w][u]) {
                        assigned[w] = true;
                        stack[sp++] = w;
                    }
                }
            }
        }
        strongComponents = count;
        stronglyConnected = count == 1;
    }

    private void dfsFinish(int u) {
        seen[u] = true;
        for (int v : d.out[u]) {
            if (!seen[v]) {
                dfsFinish(v);
            }
        }
        finishOrder[finishCount++] = u;
    }

    // ==================================================================
    // Cutpoints, bridges and blocks
    // ==================================================================
    private int[] disc, low;
    private int timer;
    private boolean[] isCut;
    private boolean[] isBridge;
    private Vector<Integer> edgeStack;
    private Vector<Vector<int[]>> adjE;

    private void findBlocks() {
        adjE = new Vector<Vector<int[]>>();
        for (int i = 0; i < n; i++) {
            adjE.add(new Vector<int[]>());
        }
        for (int e = 0; e < d.m; e++) {
            if (d.eu[e] != d.ev[e]) {
                adjE.get(d.eu[e]).add(new int[]{d.ev[e], e});
                adjE.get(d.ev[e]).add(new int[]{d.eu[e], e});
            }
        }
        disc = new int[n];
        low = new int[n];
        Arrays.fill(disc, -1);
        timer = 0;
        isCut = new boolean[n];
        isBridge = new boolean[d.m];
        edgeStack = new Vector<Integer>();
        for (int s = 0; s < n; s++) {
            if (disc[s] == -1) {
                bcc(s, -1);
            }
        }
        for (int v = 0; v < n; v++) {
            if (isCut[v]) {
                cutVertices.add(v);
            }
        }
        for (int e = 0; e < d.m; e++) {
            if (isBridge[e]) {
                bridges.add(e);
            }
        }
        nonseparable = connected && n >= 2 && cutVertices.isEmpty();
    }

    private void bcc(int u, int parentEdge) {
        disc[u] = low[u] = timer++;
        int children = 0;
        for (int[] ve : adjE.get(u)) {
            int v = ve[0], e = ve[1];
            if (e == parentEdge) {
                continue;
            }
            if (disc[v] == -1) {
                children++;
                edgeStack.add(e);
                bcc(v, e);
                low[u] = Math.min(low[u], low[v]);
                if (low[v] >= disc[u]) {
                    if (parentEdge != -1 || children > 1) {
                        isCut[u] = true;
                    }
                    TreeSet<Integer> verts = new TreeSet<Integer>();
                    while (!edgeStack.isEmpty()) {
                        int x = edgeStack.remove(edgeStack.size() - 1);
                        verts.add(d.eu[x]);
                        verts.add(d.ev[x]);
                        if (x == e) {
                            break;
                        }
                    }
                    int[] b = new int[verts.size()];
                    int k = 0;
                    for (int x : verts) {
                        b[k++] = x;
                    }
                    blocks.add(b);
                }
                if (low[v] > disc[u]) {
                    isBridge[e] = true;
                }
            } else if (disc[v] < disc[u]) {
                edgeStack.add(e);
                low[u] = Math.min(low[u], disc[v]);
            }
        }
    }

    // ==================================================================
    // Simple facts
    // ==================================================================
    private boolean hasParallel() {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && d.mult[i][j] > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private int parallelPairs() {
        int c = 0;
        for (int i = 0; i < n; i++) {
            for (int j = d.directed ? 0 : i + 1; j < n; j++) {
                if (i != j && d.mult[i][j] > 1) {
                    c++;
                }
            }
        }
        return c;
    }

    private double computeDensity() {
        if (n < 2) {
            return 0;
        }
        double max = d.directed ? (double) n * (n - 1) : n * (n - 1) / 2.0;
        return d.distinctArcs() / max;
    }

    // ==================================================================
    // Graph families
    // ==================================================================
    private void classifyFamilies() {
        complete = true;
        for (int i = 0; i < n && complete; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && !d.arc[i][j]) {
                    complete = false;
                    break;
                }
            }
        }
        empty = d.m == 0;

        boolean uSimple = d.isUnderlyingSimple();

        cycleGraph = false;
        if (d.directed) {
            if (n >= 2 && simple && stronglyConnected) {
                cycleGraph = true;
                for (int v = 0; v < n; v++) {
                    if (d.indeg[v] != 1 || d.outdeg[v] != 1) {
                        cycleGraph = false;
                    }
                }
            }
        } else if (n >= 3 && uSimple && connected) {
            cycleGraph = true;
            for (int v = 0; v < n; v++) {
                if (d.degree[v] != 2) {
                    cycleGraph = false;
                }
            }
        }

        color2 = new int[n];
        Arrays.fill(color2, -1);
        bipartite = d.totalLoops == 0;
        for (int s = 0; s < n && bipartite; s++) {
            if (color2[s] != -1) {
                continue;
            }
            color2[s] = 0;
            int[] queue = new int[n];
            int head = 0, tail = 0;
            queue[tail++] = s;
            while (head < tail && bipartite) {
                int u = queue[head++];
                for (int v : d.nbr[u]) {
                    if (color2[v] == -1) {
                        color2[v] = 1 - color2[u];
                        queue[tail++] = v;
                    } else if (color2[v] == color2[u]) {
                        bipartite = false;
                        break;
                    }
                }
            }
        }
        completeBipartite = false;
        if (bipartite && connected && n >= 2 && uSimple) {
            int p = 0, q = 0;
            for (int v = 0; v < n; v++) {
                if (color2[v] == 0) {
                    p++;
                } else {
                    q++;
                }
            }
            completeBipartite = q > 0 && d.m == p * q;
        }

        forest = d.totalLoops == 0 && d.m == n - components;
        tree = forest && connected;

        star = false;
        centre = -1;
        if (n >= 2 && uSimple && tree) {
            for (int v = 0; v < n; v++) {
                if (d.nbr[v].length == n - 1) {
                    star = true;
                    centre = v;
                    break;
                }
            }
        }

        if (d.directed) {
            cyclic = hasDirectedCycle();
        } else {
            cyclic = !forest;
        }
    }

    private boolean hasDirectedCycle() {
        if (d.totalLoops > 0) {
            return true;
        }
        int[] indeg = new int[n];
        for (int u = 0; u < n; u++) {
            for (int v : d.out[u]) {
                indeg[v]++;
            }
        }
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int v = 0; v < n; v++) {
            if (indeg[v] == 0) {
                queue[tail++] = v;
            }
        }
        while (head < tail) {
            int u = queue[head++];
            for (int v : d.out[u]) {
                if (--indeg[v] == 0) {
                    queue[tail++] = v;
                }
            }
        }
        return tail < n;
    }

    // ==================================================================
    // Connectivity numbers
    // ==================================================================
    private void computeConnectivityNumbers() {
        if (n > CONNECTIVITY_LIMIT) {
            return;
        }
        boolean linked = d.directed ? stronglyConnected : connected;
        if (n == 1 || !linked) {
            vertexConnectivity = 0;
            edgeConnectivity = 0;
            return;
        }
        int[][] cap = new int[n][n];
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                if (u != v) {
                    cap[u][v] = d.mult[u][v];
                }
            }
        }
        int best = Integer.MAX_VALUE;
        for (int t = 1; t < n; t++) {
            best = Math.min(best, maxFlow(cap, 0, t));
            if (d.directed) {
                best = Math.min(best, maxFlow(cap, t, 0));
            }
        }
        edgeConnectivity = best;

        if (complete) {
            vertexConnectivity = n - 1;
            return;
        }
        int kappa = n - 1;
        for (int s = 0; s < n; s++) {
            for (int t = 0; t < n; t++) {
                if (s == t || d.arc[s][t] || (!d.directed && t < s)) {
                    continue;
                }
                kappa = Math.min(kappa, localVertexConnectivity(s, t));
            }
        }
        vertexConnectivity = kappa;
    }

    private int localVertexConnectivity(int s, int t) {
        int big = n + 1;
        int N = 2 * n;
        int[][] cap = new int[N][N];
        for (int v = 0; v < n; v++) {
            cap[2 * v][2 * v + 1] = (v == s || v == t) ? big : 1;
        }
        for (int u = 0; u < n; u++) {
            for (int v : d.out[u]) {
                cap[2 * u + 1][2 * v] = big;
            }
        }
        return maxFlow(cap, 2 * s + 1, 2 * t);
    }

    private static int maxFlow(int[][] capIn, int s, int t) {
        int N = capIn.length;
        int[][] cap = new int[N][];
        for (int i = 0; i < N; i++) {
            cap[i] = capIn[i].clone();
        }
        int flow = 0;
        int[] parent = new int[N];
        int[] queue = new int[N];
        while (true) {
            Arrays.fill(parent, -1);
            parent[s] = s;
            int head = 0, tail = 0;
            queue[tail++] = s;
            while (head < tail && parent[t] == -1) {
                int u = queue[head++];
                for (int v = 0; v < N; v++) {
                    if (parent[v] == -1 && cap[u][v] > 0) {
                        parent[v] = u;
                        queue[tail++] = v;
                    }
                }
            }
            if (parent[t] == -1) {
                return flow;
            }
            int push = Integer.MAX_VALUE;
            for (int v = t; v != s; v = parent[v]) {
                push = Math.min(push, cap[parent[v]][v]);
            }
            for (int v = t; v != s; v = parent[v]) {
                cap[parent[v]][v] -= push;
                cap[v][parent[v]] += push;
            }
            flow += push;
        }
    }

    // ==================================================================
    // Eulerian / Hamiltonian
    // ==================================================================
    private String eulerNote = "";

    private void classifyTraversal() {
        eulerian = false;
        semiEulerian = false;
        int withEdges = 0;
        boolean[] compHasEdge = new boolean[components];
        for (int v = 0; v < n; v++) {
            if (d.degree[v] > 0) {
                compHasEdge[compOf[v]] = true;
            }
        }
        for (boolean b : compHasEdge) {
            if (b) {
                withEdges++;
            }
        }
        if (d.m == 0) {
            eulerNote = "no edges";
        } else if (withEdges > 1) {
            eulerNote = "edges lie in " + withEdges + " separate components";
        } else if (!d.directed) {
            int odd = 0;
            for (int v = 0; v < n; v++) {
                if (d.degree[v] % 2 != 0) {
                    odd++;
                }
            }
            if (odd == 0) {
                eulerian = true;
                eulerNote = "every vertex has even degree";
            } else if (odd == 2) {
                semiEulerian = true;
                eulerNote = "exactly 2 odd-degree vertices: an Euler trail exists, but not a circuit";
            } else {
                eulerNote = odd + " vertices have odd degree";
            }
        } else {
            int plus = 0, minus = 0, bad = 0;
            for (int v = 0; v < n; v++) {
                int diff = d.outdeg[v] - d.indeg[v];
                if (diff == 0) {
                    continue;
                }
                if (diff == 1) {
                    plus++;
                } else if (diff == -1) {
                    minus++;
                } else {
                    bad++;
                }
            }
            if (bad == 0 && plus == 0 && minus == 0) {
                eulerian = true;
                eulerNote = "in-degree = out-degree at every vertex";
            } else if (bad == 0 && plus == 1 && minus == 1) {
                semiEulerian = true;
                eulerNote = "an Euler trail exists, but not a circuit";
            } else {
                eulerNote = "in-degree and out-degree are unbalanced";
            }
        }

        hamiltonianChecked = n <= HAMILTONIAN_LIMIT && n >= (d.directed ? 2 : 3);
        hamiltonian = hamiltonianChecked && hamCycle(d.arc, n, d.directed);
        traceable = n <= HAMILTONIAN_LIMIT && hamPath(d.arc, n);

        maxNonHamChecked = hamiltonianChecked && n <= MAX_NON_HAM_LIMIT;
        maximalNonHamiltonian = false;
        if (maxNonHamChecked && !hamiltonian) {
            boolean all = true;
            boolean[][] a = new boolean[n][];
            for (int i = 0; i < n; i++) {
                a[i] = d.arc[i].clone();
            }
            for (int i = 0; i < n && all; i++) {
                for (int j = d.directed ? 0 : i + 1; j < n && all; j++) {
                    if (i == j || a[i][j]) {
                        continue;
                    }
                    a[i][j] = true;
                    if (!d.directed) {
                        a[j][i] = true;
                    }
                    if (!hamCycle(a, n, d.directed)) {
                        all = false;
                    }
                    a[i][j] = false;
                    if (!d.directed) {
                        a[j][i] = false;
                    }
                }
            }
            maximalNonHamiltonian = all;
        }
    }

    static boolean hamCycle(boolean[][] a, int n, boolean directed) {
        if (n < (directed ? 2 : 3)) {
            return false;
        }
        int full = (1 << n) - 1;
        int[] dp = new int[1 << n];
        dp[1] = 1;
        for (int mask = 1; mask <= full; mask += 2) {
            int ends = dp[mask];
            if (ends == 0) {
                continue;
            }
            for (int v = 0; v < n; v++) {
                if ((ends & (1 << v)) == 0) {
                    continue;
                }
                for (int w = 1; w < n; w++) {
                    if ((mask & (1 << w)) == 0 && a[v][w]) {
                        dp[mask | (1 << w)] |= (1 << w);
                    }
                }
            }
        }
        for (int v = 1; v < n; v++) {
            if ((dp[full] & (1 << v)) != 0 && a[v][0]) {
                return true;
            }
        }
        return false;
    }

    static boolean hamPath(boolean[][] a, int n) {
        if (n <= 1) {
            return n == 1;
        }
        int full = (1 << n) - 1;
        int[] dp = new int[1 << n];
        for (int v = 0; v < n; v++) {
            dp[1 << v] = 1 << v;
        }
        for (int mask = 1; mask <= full; mask++) {
            int ends = dp[mask];
            if (ends == 0) {
                continue;
            }
            for (int v = 0; v < n; v++) {
                if ((ends & (1 << v)) == 0) {
                    continue;
                }
                for (int w = 0; w < n; w++) {
                    if ((mask & (1 << w)) == 0 && a[v][w]) {
                        dp[mask | (1 << w)] |= (1 << w);
                    }
                }
            }
        }
        return dp[full] != 0;
    }

    // ==================================================================
    // Complement
    // ==================================================================
    private int complementEdges;

    private void complementInfo() {
        complementEdges = (d.directed ? n * (n - 1) : n * (n - 1) / 2) - d.distinctArcs();
        selfComplementary = false;
        if (!d.isSimple()) {
            complementNote = "no (the graph is not simple)";
            return;
        }
        if (complementEdges != d.m) {
            complementNote = "no (a graph and its complement need the same number of edges)";
            return;
        }
        GraphCompare.Match r = GraphCompare.isomorphism(d, GraphCompare.complement(d));
        if (r.map != null) {
            selfComplementary = true;
            complementNote = "yes";
        } else if (r.exhausted) {
            complementNote = "undetermined (search limit reached)";
        } else {
            complementNote = "no";
        }
    }

    // ==================================================================
    // Centrality
    // ==================================================================
    private void computeCentralities() {
        degreeCentrality = new double[n];
        closeness = new double[n];
        for (int v = 0; v < n; v++) {
            degreeCentrality[v] = n > 1 ? (double) d.nbr[v].length / (n - 1) : 0;
            double[] dist = d.distFrom(v);
            int reach = 0;
            double sum = 0;
            for (int w = 0; w < n; w++) {
                if (w != v && dist[w] < GraphData.INF) {
                    reach++;
                    sum += dist[w];
                }
            }
            closeness[v] = (reach > 0 && n > 1 && sum > 0) ? ((double) reach / sum) * ((double) reach / (n - 1)) : 0;
        }
        betweenness = brandes();
    }

    private double[] brandes() {
        double[] cb = new double[n];
        for (int s = 0; s < n; s++) {
            double[] dist = new double[n];
            Arrays.fill(dist, GraphData.INF);
            double[] sigma = new double[n];
            boolean[] done = new boolean[n];
            Vector<Vector<Integer>> pred = new Vector<Vector<Integer>>();
            for (int i = 0; i < n; i++) {
                pred.add(new Vector<Integer>());
            }
            Vector<Integer> stack = new Vector<Integer>();
            dist[s] = 0;
            sigma[s] = 1;
            for (int it = 0; it < n; it++) {
                int u = -1;
                for (int i = 0; i < n; i++) {
                    if (!done[i] && dist[i] < GraphData.INF && (u == -1 || dist[i] < dist[u])) {
                        u = i;
                    }
                }
                if (u == -1) {
                    break;
                }
                done[u] = true;
                stack.add(u);
                for (int v : d.out[u]) {
                    double nd = dist[u] + d.arcLength(u, v);
                    if (nd < dist[v] - EPS) {
                        dist[v] = nd;
                        sigma[v] = sigma[u];
                        pred.get(v).clear();
                        pred.get(v).add(u);
                    } else if (Math.abs(nd - dist[v]) <= EPS && !done[v]) {
                        sigma[v] += sigma[u];
                        pred.get(v).add(u);
                    }
                }
            }
            double[] delta = new double[n];
            for (int k = stack.size() - 1; k >= 0; k--) {
                int w = stack.get(k);
                for (int v : pred.get(w)) {
                    delta[v] += sigma[v] / sigma[w] * (1 + delta[w]);
                }
                if (w != s) {
                    cb[w] += delta[w];
                }
            }
        }
        double norm = 1;
        if (n > 2) {
            norm = d.directed ? (double) (n - 1) * (n - 2) : (double) (n - 1) * (n - 2) / 2.0;
        }
        for (int v = 0; v < n; v++) {
            if (n <= 2) {
                cb[v] = 0;
            } else {
                cb[v] = (d.directed ? cb[v] : cb[v] / 2.0) / norm;
            }
        }
        return cb;
    }

    // ==================================================================
    // Node table (Vertices tab)
    // ==================================================================
    private void buildNodeTable() {
        Vector<String> cols = new Vector<String>();
        Vector<Class<?>> classes = new Vector<Class<?>>();
        cols.add("Vertex");
        classes.add(String.class);
        cols.add("Degree");
        classes.add(Integer.class);
        if (d.directed) {
            cols.add("In");
            classes.add(Integer.class);
            cols.add("Out");
            classes.add(Integer.class);
        }
        cols.add("Isolated");
        classes.add(String.class);
        cols.add("Loop");
        classes.add(String.class);
        cols.add("Cutpoint");
        classes.add(String.class);
        cols.add("Degree C.");
        classes.add(Double.class);
        cols.add("Closeness");
        classes.add(Double.class);
        cols.add("Betweenness");
        classes.add(Double.class);

        nodeColumns = cols.toArray(new String[0]);
        nodeClasses = classes.toArray(new Class<?>[0]);
        nodeRows = new Object[n][];
        for (int v = 0; v < n; v++) {
            ArrayList<Object> row = new ArrayList<Object>();
            row.add(d.names[v]);
            row.add(d.degree[v]);
            if (d.directed) {
                row.add(d.indeg[v]);
                row.add(d.outdeg[v]);
            }
            row.add(d.degree[v] == 0 ? "yes" : "no");
            row.add(d.loops[v] > 0 ? "yes" : "no");
            row.add(isCut[v] ? "yes" : "no");
            row.add(round2(degreeCentrality[v]));
            row.add(round2(closeness[v]));
            row.add(round2(betweenness[v]));
            nodeRows[v] = row.toArray();
        }
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    // ==================================================================
    // Overview rows
    // ==================================================================
    private String yn(boolean b) {
        return b ? "yes" : "no";
    }

    private int st(boolean b) {
        return b ? YES : NO;
    }

    private String names(Vector<Integer> vs) {
        if (vs.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(d.names[vs.get(i)]);
        }
        return sb.toString();
    }

    private String edgeName(int e) {
        return "(" + d.names[d.eu[e]] + (d.directed ? "\u2192" : "\u2013") + d.names[d.ev[e]] + ")";
    }

    private String setString(int[] vs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < vs.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(d.names[vs[i]]);
        }
        return sb.append("}").toString();
    }

    private void buildOverview() {
        Vector<Row> r = overview;

        // ---------------- Basics ----------------
        r.add(Row.section("Basics", Ui.GROUP_BASIC, "Size and shape of the graph"));
        r.add(Row.prop("Type", (d.directed ? "Directed" : "Undirected") + ", " + (d.weighted ? "weighted" : "unweighted"), NONE,
                "Directed: edges are arcs with a tail and a head. Weighted: each edge carries a positive weight used as its length."));
        r.add(Row.prop("Order |V|", String.valueOf(order), NONE, "Number of vertices."));
        r.add(Row.prop("Size |E|", String.valueOf(size), NONE, "Number of edges (loops and parallel edges each count)."));
        String simpleWhy = "";
        if (!simple) {
            Vector<String> why = new Vector<String>();
            if (d.totalLoops > 0) {
                why.add("loops");
            }
            if (multigraph) {
                why.add("parallel edges");
            }
            simpleWhy = " \u2014 has " + join(why, " and ");
        }
        r.add(Row.prop("Simple graph", yn(simple) + simpleWhy, st(simple),
                "A simple graph has no self-loops and no two edges joining the same ordered (directed) / unordered (undirected) pair."));
        r.add(Row.prop("Multigraph", multigraph ? "yes \u2014 " + parallelPairs() + " pair(s) with parallel edges"
                + (d.totalLoops > 0 ? ", plus loops (pseudograph)" : "")
                : (d.totalLoops > 0 ? "no parallel edges, but has loops (pseudograph)" : "no"), st(multigraph),
                "A multigraph allows several edges between the same two vertices. If loops are allowed too it is called a pseudograph."));
        if (d.weighted && d.m > 0) {
            double sum = 0, mn = GraphData.INF, mx = 0;
            for (double w : d.ew) {
                sum += w;
                mn = Math.min(mn, w);
                mx = Math.max(mx, w);
            }
            r.add(Row.prop("Weights", "total " + Ui.fmt(sum) + ", min " + Ui.fmt(mn) + ", max " + Ui.fmt(mx), NONE,
                    "Weights are positive. Shortest paths (geodesics) use the lightest edge between each pair."));
        }
        String dens;
        if (n < 2) {
            dens = "n/a";
        } else {
            String label = density <= 0.3 ? "Sparse" : density >= 0.7 ? "Dense" : "Moderate";
            dens = label + " (" + Math.round(density * 100) + "% of possible edges)";
        }
        r.add(Row.prop("Sparse / Dense", dens, NONE,
                "Density = distinct adjacent pairs / possible pairs. Sparse is 30% or less, dense is 70% or more."));

        // ---------------- Connectivity ----------------
        r.add(Row.section("Connectivity", Ui.GROUP_CONNECT, "How well the graph holds together"));
        if (d.directed) {
            r.add(Row.prop("Connected (weakly)", yn(connected), st(connected),
                    "Weakly connected: the graph is connected when edge directions are ignored."));
            r.add(Row.prop("Strongly connected", yn(stronglyConnected), st(stronglyConnected),
                    "Strongly connected: every vertex can reach every other vertex along directed edges."));
            r.add(Row.prop("Components", "weak " + components + ", strong " + strongComponents, NONE,
                    "Number of weakly and strongly connected components."));
        } else {
            r.add(Row.prop("Connected", yn(connected), st(connected), "There is a path between every pair of vertices."));
            StringBuilder cs = new StringBuilder();
            for (Vector<Integer> comp : compList) {
                if (cs.length() > 0) {
                    cs.append(" ");
                }
                int[] a = new int[comp.size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = comp.get(i);
                }
                cs.append(setString(a));
            }
            r.add(Row.prop("Components", components + " \u2014 " + cs, NONE,
                    "Maximal connected pieces of the graph: " + cs));
        }
        r.add(Row.prop("Vertex connectivity \u03BA", vertexConnectivity < 0 ? "not computed (more than " + CONNECTIVITY_LIMIT + " vertices)"
                : String.valueOf(vertexConnectivity), NONE,
                "Minimum number of vertices whose removal disconnects the graph (or leaves a single vertex). Computed with max-flow / Menger's theorem."));
        r.add(Row.prop("Edge connectivity \u03BB", edgeConnectivity < 0 ? "not computed (more than " + CONNECTIVITY_LIMIT + " vertices)"
                : String.valueOf(edgeConnectivity), NONE,
                "Minimum number of edges whose removal disconnects the graph."));
        StringBuilder bs = new StringBuilder();
        for (int i = 0; i < bridges.size(); i++) {
            if (i > 0) {
                bs.append(", ");
            }
            bs.append(edgeName(bridges.get(i)));
        }
        r.add(Row.prop("Bridges", bridges.isEmpty() ? "none" : bridges.size() + " \u2014 " + bs, bridges.isEmpty() ? NO : WARN,
                "A bridge is an edge whose removal increases the number of components (judged on the underlying undirected graph)."
                + (bridges.isEmpty() ? "" : " Bridges: " + bs)));
        r.add(Row.prop("Cutpoints", cutVertices.isEmpty() ? "none" : cutVertices.size() + " \u2014 " + names(cutVertices),
                cutVertices.isEmpty() ? NO : WARN,
                "A cutpoint (articulation vertex) is a vertex whose removal increases the number of components. Highlighted orange on the canvas."));
        StringBuilder bl = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                bl.append(" ");
            }
            bl.append(setString(blocks.get(i)));
        }
        r.add(Row.prop("Blocks", blocks.isEmpty() ? "none" : blocks.size() + " \u2014 " + bl, NONE,
                "A block is a maximal nonseparable subgraph (a maximal piece with no cutpoint). Blocks: " + bl));
        r.add(Row.prop("Nonseparable", yn(nonseparable), st(nonseparable),
                "A nonseparable graph is connected, has at least 2 vertices and no cutpoints (it is a single block)."));

        // ---------------- Families ----------------
        r.add(Row.section("Graph families", Ui.GROUP_FAMILY, "Special named graph classes"));
        r.add(Row.prop("Complete graph", complete ? "yes \u2014 K" + sub(n) : "no", st(complete),
                "Every pair of distinct vertices is adjacent" + (d.directed ? " in both directions." : ".")));
        r.add(Row.prop("Empty graph", empty ? "yes \u2014 E" + sub(n) : "no", st(empty), "A graph with vertices but no edges."));
        r.add(Row.prop("Cycle graph", cycleGraph ? "yes \u2014 C" + sub(n) : "no", st(cycleGraph),
                "A single cycle through all vertices (every vertex has degree 2 in an undirected graph; in-degree = out-degree = 1 in a digraph)."));
        String bip;
        if (bipartite) {
            Vector<Integer> a = new Vector<Integer>(), b = new Vector<Integer>();
            for (int v = 0; v < n; v++) {
                (color2[v] == 0 ? a : b).add(v);
            }
            bip = "yes \u2014 {" + names(a) + "} | {" + names(b) + "}";
        } else {
            bip = d.totalLoops > 0 ? "no \u2014 a loop is an odd cycle" : "no \u2014 contains an odd cycle";
        }
        r.add(Row.prop("Bipartite", bip, st(bipartite), "The vertices split into two sets with every edge going between the sets (no odd cycle)."));
        String kpq = "";
        if (completeBipartite) {
            int p = 0;
            for (int v = 0; v < n; v++) {
                if (color2[v] == 0) {
                    p++;
                }
            }
            kpq = " \u2014 K" + sub(p) + "," + sub(n - p);
        }
        r.add(Row.prop("Complete bipartite", yn(completeBipartite) + kpq, st(completeBipartite),
                "Bipartite, and every vertex of one side is adjacent to every vertex of the other."));
        r.add(Row.prop("Star", star ? "yes \u2014 K" + sub(1) + "," + sub(n - 1) + " (centre " + d.names[centre] + ")" : "no", st(star),
                "One centre vertex joined to every other vertex, with no other edges."));
        r.add(Row.prop("Tree", yn(tree), st(tree), "Connected and acyclic (judged on the underlying undirected graph)."));
        r.add(Row.prop("Forest", forest ? "yes \u2014 " + components + " tree(s)" : "no", st(forest),
                "Acyclic: every component is a tree."));
        r.add(Row.prop("Cyclic / Acyclic", cyclic ? "Cyclic" : "Acyclic", cyclic ? WARN : YES,
                d.directed ? "Directed graphs: cyclic means a directed cycle exists (acyclic = DAG)."
                : "Cyclic means the graph contains a cycle (parallel edges and loops count as cycles)."));

        // ---------------- Traversability ----------------
        r.add(Row.section("Traversability", Ui.GROUP_TRAVERSE, "Walks that visit every edge or every vertex"));
        String eul;
        int eulState;
        if (eulerian) {
            eul = "yes \u2014 Euler circuit (" + eulerNote + ")";
            eulState = YES;
        } else if (semiEulerian) {
            eul = "semi \u2014 " + eulerNote;
            eulState = WARN;
        } else {
            eul = "no \u2014 " + eulerNote;
            eulState = NO;
        }
        r.add(Row.prop("Eulerian", eul, eulState,
                "Eulerian: a closed trail that uses every edge exactly once. Semi-Eulerian: an open trail does, but no circuit exists."));
        String ham;
        int hamState;
        if (!hamiltonianChecked) {
            ham = n < 3 ? "n/a (needs at least 3 vertices)" : "not checked (more than " + HAMILTONIAN_LIMIT + " vertices)";
            hamState = NONE;
        } else {
            ham = yn(hamiltonian);
            hamState = st(hamiltonian);
        }
        r.add(Row.prop("Hamiltonian", ham, hamState, "A cycle that visits every vertex exactly once."));
        r.add(Row.prop("Traceable", n > HAMILTONIAN_LIMIT ? "not checked" : yn(traceable), n > HAMILTONIAN_LIMIT ? NONE : st(traceable),
                "A path (not necessarily closed) that visits every vertex exactly once."));
        String mnh;
        int mnhState;
        if (!maxNonHamChecked) {
            mnh = n < 3 ? "n/a" : "not checked (more than " + MAX_NON_HAM_LIMIT + " vertices)";
            mnhState = NONE;
        } else if (hamiltonian) {
            mnh = "no \u2014 the graph is Hamiltonian";
            mnhState = NO;
        } else {
            mnh = yn(maximalNonHamiltonian);
            mnhState = st(maximalNonHamiltonian);
        }
        r.add(Row.prop("Maximal non-Hamiltonian", mnh, mnhState,
                "Not Hamiltonian, but adding any missing edge makes it Hamiltonian."));

        // ---------------- Transformations ----------------
        r.add(Row.section("Transformations", Ui.GROUP_OTHER, "Operations and related graphs"));
        r.add(Row.prop("Complement", "complement has " + complementEdges + " edge(s)  (Graph \u2192 Replace with Complement)", NONE,
                "The complement joins exactly the pairs that are NOT adjacent in G."));
        r.add(Row.prop("Self-complementary", complementNote, selfComplementary ? YES : NONE,
                "G is isomorphic to its own complement."));
    }

    private static String join(Vector<String> v, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(v.get(i));
        }
        return sb.toString();
    }

    private static String sub(int k) {
        String digits = "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089";
        String s = String.valueOf(k);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(digits.charAt(s.charAt(i) - '0'));
        }
        return sb.toString();
    }
}