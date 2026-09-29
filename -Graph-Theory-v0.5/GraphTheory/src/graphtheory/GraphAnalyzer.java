/*
 * Computes graph-level and node-level properties and renders them
 * as text summaries for the "Graph Info" window / console.
 */
package graphtheory;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

public class GraphAnalyzer {

    // ---------------- Graph-level properties ----------------
    public int order;
    public int size;
    public boolean connected;
    public int componentCount;
    public boolean complete;
    public boolean bipartite;
    public boolean tree;
    public boolean cyclic;
    public boolean sparse;
    public boolean dense;
    public boolean eulerian;
    public boolean hamiltonianChecked;
    public boolean hamiltonian;
    public int vertexConnectivity;   // N(G), -1 if not computed
    public int edgeConnectivity;     // O(G), -1 if not computed
    public Vector<Vertex> cutVertices = new Vector<Vertex>();
    public Vector<Edge> bridges = new Vector<Edge>();
    public Vector<Vertex> isolatedVertices = new Vector<Vertex>();

    // ---------------- Node-level properties ----------------
    public Map<Vertex, Integer> degreeMap = new LinkedHashMap<Vertex, Integer>();
    public Map<Vertex, Double> centralityMap = new LinkedHashMap<Vertex, Double>();
    public Map<Vertex, Boolean> cutVertexMap = new LinkedHashMap<Vertex, Boolean>();

    // Ready-to-print / ready-to-draw text
    public Vector<String> graphSummary = new Vector<String>();
    public Vector<String> nodeSummary = new Vector<String>();

    // Brute-force checks are exponential, so cap the vertex count they run on
    private static final int HAMILTONIAN_LIMIT = 10;
    private static final int CONNECTIVITY_LIMIT = 12;

    public void analyze(Vector<Vertex> vList, Vector<Edge> eList) {
        order = vList.size();
        size = eList.size();

        cutVertices.clear();
        bridges.clear();
        isolatedVertices.clear();
        degreeMap.clear();
        centralityMap.clear();
        cutVertexMap.clear();
        graphSummary.clear();
        nodeSummary.clear();

        if (order == 0) {
            return;
        }

        for (Vertex v : vList) {
            int d = v.getDegree();
            degreeMap.put(v, d);
            centralityMap.put(v, order > 1 ? round2((double) d / (order - 1)) : 0.0);
            if (d == 0) {
                isolatedVertices.add(v);
            }
        }

        connected = isConnected(vList);
        componentCount = countComponents(vList);
        complete = isComplete(vList);
        bipartite = isBipartite(vList);
        cyclic = hasCycle(vList);
        tree = connected && !cyclic;

        double density = order > 1 ? (2.0 * size) / ((double) order * (order - 1)) : 0;
        sparse = density <= 0.3;
        dense = density >= 0.7;

        eulerian = connected && allDegreesEven(vList);

        cutVertices = findCutVertices(vList);
        for (Vertex v : vList) {
            cutVertexMap.put(v, cutVertices.contains(v));
        }

        bridges = findBridges(vList, eList);

        vertexConnectivity = (connected && order <= CONNECTIVITY_LIMIT) ? computeVertexConnectivity(vList) : -1;
        edgeConnectivity = (connected && order <= CONNECTIVITY_LIMIT) ? computeEdgeConnectivity(vList, eList) : -1;

        hamiltonianChecked = order >= 3 && order <= HAMILTONIAN_LIMIT;
        hamiltonian = hamiltonianChecked && hasHamiltonianCycle(vList);

        buildSummaries(vList);
        printReport();
    }

    private double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    // ---------------- Connectivity / structure checks ----------------

    private boolean isConnected(Vector<Vertex> vList) {
        return countComponents(vList) <= 1;
    }

    private int countComponents(Vector<Vertex> vList) {
        Set<Vertex> visited = new HashSet<Vertex>();
        int count = 0;
        for (Vertex start : vList) {
            if (!visited.contains(start)) {
                count++;
                Deque<Vertex> stack = new ArrayDeque<Vertex>();
                stack.push(start);
                visited.add(start);
                while (!stack.isEmpty()) {
                    Vertex v = stack.pop();
                    for (Vertex n : v.connectedVertices) {
                        if (!visited.contains(n)) {
                            visited.add(n);
                            stack.push(n);
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean isComplete(Vector<Vertex> vList) {
        int n = vList.size();
        if (n <= 1) {
            return true;
        }
        for (Vertex v : vList) {
            if (v.getDegree() != n - 1) {
                return false;
            }
        }
        return true;
    }

    private boolean isBipartite(Vector<Vertex> vList) {
        Map<Vertex, Integer> color = new HashMap<Vertex, Integer>();
        for (Vertex start : vList) {
            if (color.containsKey(start)) {
                continue;
            }
            color.put(start, 0);
            Deque<Vertex> queue = new ArrayDeque<Vertex>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Vertex v = queue.poll();
                for (Vertex n : v.connectedVertices) {
                    if (!color.containsKey(n)) {
                        color.put(n, 1 - color.get(v));
                        queue.add(n);
                    } else if (color.get(n).equals(color.get(v))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean hasCycle(Vector<Vertex> vList) {
        Set<Vertex> visited = new HashSet<Vertex>();
        for (Vertex start : vList) {
            if (!visited.contains(start)) {
                if (cycleDFS(start, null, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean cycleDFS(Vertex v, Vertex parent, Set<Vertex> visited) {
        visited.add(v);
        for (Vertex n : v.connectedVertices) {
            if (!visited.contains(n)) {
                if (cycleDFS(n, v, visited)) {
                    return true;
                }
            } else if (n != parent) {
                return true;
            }
        }
        return false;
    }

    private boolean allDegreesEven(Vector<Vertex> vList) {
        for (Vertex v : vList) {
            if (v.getDegree() % 2 != 0) {
                return false;
            }
        }
        return true;
    }

    // Articulation points via DFS low-link
    private Vector<Vertex> findCutVertices(Vector<Vertex> vList) {
        Vector<Vertex> result = new Vector<Vertex>();
        if (vList.isEmpty()) {
            return result;
        }

        Map<Vertex, Integer> disc = new HashMap<Vertex, Integer>();
        Map<Vertex, Integer> low = new HashMap<Vertex, Integer>();
        Map<Vertex, Vertex> parent = new HashMap<Vertex, Vertex>();
        Set<Vertex> visited = new HashSet<Vertex>();
        Set<Vertex> articulation = new HashSet<Vertex>();
        int[] timer = {0};

        for (Vertex start : vList) {
            if (!visited.contains(start)) {
                apDFS(start, visited, disc, low, parent, articulation, timer);
            }
        }
        for (Vertex v : vList) {
            if (articulation.contains(v)) {
                result.add(v);
            }
        }
        return result;
    }

    private void apDFS(Vertex u, Set<Vertex> visited, Map<Vertex, Integer> disc, Map<Vertex, Integer> low,
            Map<Vertex, Vertex> parent, Set<Vertex> articulation, int[] timer) {
        visited.add(u);
        disc.put(u, timer[0]);
        low.put(u, timer[0]);
        timer[0]++;
        int children = 0;

        for (Vertex v : u.connectedVertices) {
            if (!visited.contains(v)) {
                children++;
                parent.put(v, u);
                apDFS(v, visited, disc, low, parent, articulation, timer);
                low.put(u, Math.min(low.get(u), low.get(v)));

                if (parent.get(u) == null && children > 1) {
                    articulation.add(u);
                }
                if (parent.get(u) != null && low.get(v) >= disc.get(u)) {
                    articulation.add(u);
                }
            } else if (v != parent.get(u)) {
                low.put(u, Math.min(low.get(u), disc.get(v)));
            }
        }
    }

    // Bridges via DFS low-link
    private Vector<Edge> findBridges(Vector<Vertex> vList, Vector<Edge> eList) {
        Vector<Edge> result = new Vector<Edge>();
        if (vList.isEmpty()) {
            return result;
        }

        Map<Vertex, Integer> disc = new HashMap<Vertex, Integer>();
        Map<Vertex, Integer> low = new HashMap<Vertex, Integer>();
        Map<Vertex, Vertex> parent = new HashMap<Vertex, Vertex>();
        Set<Vertex> visited = new HashSet<Vertex>();
        Set<String> bridgeKeys = new HashSet<String>();
        int[] timer = {0};

        for (Vertex start : vList) {
            if (!visited.contains(start)) {
                bridgeDFS(start, visited, disc, low, parent, bridgeKeys, timer);
            }
        }

        for (Edge e : eList) {
            String k1 = e.vertex1.name + "|" + e.vertex2.name;
            String k2 = e.vertex2.name + "|" + e.vertex1.name;
            if (bridgeKeys.contains(k1) || bridgeKeys.contains(k2)) {
                result.add(e);
            }
        }
        return result;
    }

    private void bridgeDFS(Vertex u, Set<Vertex> visited, Map<Vertex, Integer> disc, Map<Vertex, Integer> low,
            Map<Vertex, Vertex> parent, Set<String> bridgeKeys, int[] timer) {
        visited.add(u);
        disc.put(u, timer[0]);
        low.put(u, timer[0]);
        timer[0]++;

        for (Vertex v : u.connectedVertices) {
            if (!visited.contains(v)) {
                parent.put(v, u);
                bridgeDFS(v, visited, disc, low, parent, bridgeKeys, timer);
                low.put(u, Math.min(low.get(u), low.get(v)));
                if (low.get(v) > disc.get(u)) {
                    bridgeKeys.add(u.name + "|" + v.name);
                }
            } else if (v != parent.get(u)) {
                low.put(u, Math.min(low.get(u), disc.get(v)));
            }
        }
    }

    // Brute-force minimum vertex cut size (small graphs only)
    private int computeVertexConnectivity(Vector<Vertex> vList) {
        int n = vList.size();
        if (n <= 1) {
            return 0;
        }
        if (isComplete(vList)) {
            return n - 1;
        }
        for (int k = 1; k < n; k++) {
            if (existsDisconnectingVertexSubset(vList, k)) {
                return k;
            }
        }
        return n - 1;
    }

    private boolean existsDisconnectingVertexSubset(Vector<Vertex> vList, int k) {
        int[] combo = new int[k];
        return tryVertexCombo(vList, combo, 0, 0, vList.size(), k);
    }

    private boolean tryVertexCombo(Vector<Vertex> vList, int[] combo, int start, int idx, int n, int k) {
        if (idx == k) {
            Set<Vertex> removed = new HashSet<Vertex>();
            for (int i : combo) {
                removed.add(vList.get(i));
            }
            return disconnectsGraph(vList, removed, null);
        }
        for (int i = start; i < n; i++) {
            combo[idx] = i;
            if (tryVertexCombo(vList, combo, i + 1, idx + 1, n, k)) {
                return true;
            }
        }
        return false;
    }

    private boolean disconnectsGraph(Vector<Vertex> vList, Set<Vertex> removedVertices, Set<String> removedEdgeKeys) {
        Vector<Vertex> remaining = new Vector<Vertex>();
        for (Vertex v : vList) {
            if (!removedVertices.contains(v)) {
                remaining.add(v);
            }
        }
        if (remaining.isEmpty()) {
            return true;
        }

        Set<Vertex> visited = new HashSet<Vertex>();
        Deque<Vertex> stack = new ArrayDeque<Vertex>();
        Vertex startV = remaining.firstElement();
        stack.push(startV);
        visited.add(startV);
        while (!stack.isEmpty()) {
            Vertex v = stack.pop();
            for (Vertex n : v.connectedVertices) {
                if (removedVertices.contains(n)) {
                    continue;
                }
                if (removedEdgeKeys != null) {
                    String k1 = v.name + "|" + n.name;
                    String k2 = n.name + "|" + v.name;
                    if (removedEdgeKeys.contains(k1) || removedEdgeKeys.contains(k2)) {
                        continue;
                    }
                }
                if (!visited.contains(n)) {
                    visited.add(n);
                    stack.push(n);
                }
            }
        }
        return visited.size() != remaining.size();
    }

    // Brute-force minimum edge cut size (small graphs only)
    private int computeEdgeConnectivity(Vector<Vertex> vList, Vector<Edge> eList) {
        int m = eList.size();
        if (m == 0) {
            return 0;
        }
        int minDegree = Integer.MAX_VALUE;
        for (Vertex v : vList) {
            minDegree = Math.min(minDegree, v.getDegree());
        }
        for (int k = 1; k <= minDegree; k++) {
            if (existsDisconnectingEdgeSubset(vList, eList, k)) {
                return k;
            }
        }
        return minDegree;
    }

    private boolean existsDisconnectingEdgeSubset(Vector<Vertex> vList, Vector<Edge> eList, int k) {
        int[] combo = new int[k];
        return tryEdgeCombo(vList, eList, combo, 0, 0, eList.size(), k);
    }

    private boolean tryEdgeCombo(Vector<Vertex> vList, Vector<Edge> eList, int[] combo, int start, int idx, int m, int k) {
        if (idx == k) {
            Set<String> removed = new HashSet<String>();
            for (int i : combo) {
                Edge e = eList.get(i);
                removed.add(e.vertex1.name + "|" + e.vertex2.name);
            }
            return disconnectsGraph(vList, Collections.<Vertex>emptySet(), removed);
        }
        for (int i = start; i < m; i++) {
            combo[idx] = i;
            if (tryEdgeCombo(vList, eList, combo, i + 1, idx + 1, m, k)) {
                return true;
            }
        }
        return false;
    }

    // Hamiltonian cycle: backtracking search
    private boolean hasHamiltonianCycle(Vector<Vertex> vList) {
        int n = vList.size();
        if (n < 3) {
            return false;
        }
        boolean[] visited = new boolean[n];
        int[] path = new int[n];
        path[0] = 0;
        visited[0] = true;
        return hamDFS(vList, path, visited, 1, n);
    }

    private boolean hamDFS(Vector<Vertex> vList, int[] path, boolean[] visited, int pos, int n) {
        if (pos == n) {
            return vList.get(path[pos - 1]).connectedToVertex(vList.get(path[0]));
        }
        Vertex prev = vList.get(path[pos - 1]);
        for (int i = 0; i < n; i++) {
            if (!visited[i] && prev.connectedToVertex(vList.get(i))) {
                visited[i] = true;
                path[pos] = i;
                if (hamDFS(vList, path, visited, pos + 1, n)) {
                    return true;
                }
                visited[i] = false;
            }
        }
        return false;
    }

    // ---------------- Summaries ----------------

    private void buildSummaries(Vector<Vertex> vList) {
        graphSummary.add("Order |V| = " + order + "   Size |E| = " + size);
        graphSummary.add("Connected: " + yn(connected) + "   Components: " + componentCount);
        graphSummary.add("Complete: " + yn(complete) + "   Bipartite: " + yn(bipartite));
        graphSummary.add("Tree: " + yn(tree) + "   Cyclic: " + yn(cyclic));
        graphSummary.add("Sparse: " + yn(sparse) + "   Dense: " + yn(dense));
        graphSummary.add("Eulerian: " + yn(eulerian));
        graphSummary.add("Hamiltonian: "
                + (hamiltonianChecked ? yn(hamiltonian) : "not checked (>" + HAMILTONIAN_LIMIT + " vertices)"));
        graphSummary.add("Vertex connectivity N(G): "
                + (vertexConnectivity == -1 ? "not computed" : String.valueOf(vertexConnectivity)));
        graphSummary.add("Edge connectivity O(G): "
                + (edgeConnectivity == -1 ? "not computed" : String.valueOf(edgeConnectivity)));
        graphSummary.add("Cut vertices: " + namesOf(cutVertices));
        graphSummary.add("Bridges: " + edgeNamesOf(bridges));
        graphSummary.add("Isolated vertices: " + namesOf(isolatedVertices));

        nodeSummary.add(String.format("%-6s %-5s %-10s %-6s", "Vertex", "Deg", "Central.", "Cut?"));
        for (Vertex v : vList) {
            nodeSummary.add(String.format("%-6s %-5d %-10s %-6s",
                    v.name, degreeMap.get(v), String.valueOf(centralityMap.get(v)),
                    cutVertexMap.get(v) ? "yes" : "no"));
        }
    }

    private String yn(boolean b) {
        return b ? "yes" : "no";
    }

    private String namesOf(Vector<Vertex> vs) {
        if (vs.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(vs.get(i).name);
        }
        return sb.toString();
    }

    private String edgeNamesOf(Vector<Edge> es) {
        if (es.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < es.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("(").append(es.get(i).vertex1.name).append("-").append(es.get(i).vertex2.name).append(")");
        }
        return sb.toString();
    }

    private void printReport() {
        System.out.println("================ GRAPH / NODE PROPERTIES ================");
        for (String s : graphSummary) {
            System.out.println(s);
        }
        System.out.println();
        for (String s : nodeSummary) {
            System.out.println(s);
        }
        System.out.println("===========================================================");
    }

    // ---------------- Drawing ----------------

    public void drawGraphSummary(Graphics g, int x, int y, int maxY) {
        Font orig = g.getFont();
        g.setColor(Color.black);
        int lineH = 16;

        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.drawString("Graph Properties", x, y);
        y += lineH + 6;

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        for (String s : graphSummary) {
            if (y > maxY) {
                g.drawString("... see console", x, y);
                break;
            }
            g.drawString(s, x, y);
            y += lineH;
        }
        g.setFont(orig);
    }

    public void drawNodeSummary(Graphics g, int x, int y, int maxY) {
        Font orig = g.getFont();
        g.setColor(Color.black);
        int lineH = 16;

        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.drawString("Node Properties", x, y);
        y += lineH + 6;

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        for (String s : nodeSummary) {
            if (y > maxY) {
                g.drawString("... see console", x, y);
                break;
            }
            g.drawString(s, x, y);
            y += lineH;
        }
        g.setFont(orig);
    }
}