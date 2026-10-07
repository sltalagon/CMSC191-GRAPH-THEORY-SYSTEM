package graphtheory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Vector;

/**
 * The editable graph: vertices, edges and three mode flags.
 * <ul>
 * <li>{@code directed} - edges are arcs (tail = vertex1, head = vertex2)</li>
 * <li>{@code weighted} - edge weights are shown and used for shortest paths</li>
 * <li>{@code multi}    - the editor may add parallel edges and self-loops</li>
 * </ul>
 */
public class Graph {

    public Vector<Vertex> vertices = new Vector<Vertex>();
    public Vector<Edge> edges = new Vector<Edge>();
    public boolean directed;
    public boolean weighted;
    public boolean multi;

    public Graph copy() {
        Graph g = new Graph();
        g.directed = directed;
        g.weighted = weighted;
        g.multi = multi;
        Map<Vertex, Integer> idx = indexMap();
        for (Vertex v : vertices) {
            g.vertices.add(new Vertex(v.name, v.location.x, v.location.y));
        }
        for (Edge e : edges) {
            g.edges.add(new Edge(g.vertices.get(idx.get(e.vertex1)), g.vertices.get(idx.get(e.vertex2)), e.weight));
        }
        return g;
    }

    public Map<Vertex, Integer> indexMap() {
        Map<Vertex, Integer> idx = new IdentityHashMap<Vertex, Integer>();
        for (int i = 0; i < vertices.size(); i++) {
            idx.put(vertices.get(i), i);
        }
        return idx;
    }

    public Edge addEdge(Vertex a, Vertex b, double w) {
        Edge e = new Edge(a, b, w);
        edges.add(e);
        return e;
    }

    public void removeVertex(Vertex v) {
        for (int i = edges.size() - 1; i >= 0; i--) {
            Edge e = edges.get(i);
            if (e.vertex1 == v || e.vertex2 == v) {
                edges.remove(i);
            }
        }
        vertices.remove(v);
    }

    public void removeEdge(Edge e) {
        edges.remove(e);
    }

    /** Number of edges that run a -> b (directed) or join a and b (undirected). */
    public int countBetween(Vertex a, Vertex b) {
        int c = 0;
        for (Edge e : edges) {
            if (e.vertex1 == a && e.vertex2 == b) {
                c++;
            } else if (!directed && e.vertex1 == b && e.vertex2 == a && a != b) {
                c++;
            }
        }
        return c;
    }

    public int degree(Vertex v) {
        int d = 0;
        for (Edge e : edges) {
            if (e.vertex1 == v) {
                d++;
            }
            if (e.vertex2 == v) {
                d++;
            }
        }
        return d;
    }

    public int inDegree(Vertex v) {
        int d = 0;
        for (Edge e : edges) {
            if (e.vertex2 == v) {
                d++;
            }
        }
        return d;
    }

    public int outDegree(Vertex v) {
        int d = 0;
        for (Edge e : edges) {
            if (e.vertex1 == v) {
                d++;
            }
        }
        return d;
    }

    public String nextName() {
        for (int i = 0;; i++) {
            String s = String.valueOf(i);
            boolean used = false;
            for (Vertex v : vertices) {
                if (v.name.equals(s)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return s;
            }
        }
    }

    /**
     * Works out, for every edge, where it sits inside its group of parallel edges
     * (so they fan out as separate curves) and which loop number it is.
     */
    public void layoutEdges() {
        Map<Vertex, Integer> idx = indexMap();
        Map<String, Vector<Edge>> groups = new HashMap<String, Vector<Edge>>();
        Map<Vertex, Integer> loopCount = new IdentityHashMap<Vertex, Integer>();
        for (Edge e : edges) {
            if (e.isLoop()) {
                Integer c = loopCount.get(e.vertex1);
                e.loopIndex = (c == null) ? 0 : c;
                loopCount.put(e.vertex1, e.loopIndex + 1);
                continue;
            }
            int a = idx.get(e.vertex1), b = idx.get(e.vertex2);
            e.flip = a > b;
            String key = Math.min(a, b) + ":" + Math.max(a, b);
            Vector<Edge> grp = groups.get(key);
            if (grp == null) {
                grp = new Vector<Edge>();
                groups.put(key, grp);
            }
            grp.add(e);
        }
        for (Vector<Edge> grp : groups.values()) {
            for (int i = 0; i < grp.size(); i++) {
                grp.get(i).slot = i;
                grp.get(i).slotCount = grp.size();
            }
        }
        for (Edge e : edges) {
            e.layout();
        }
    }

    /** The complement graph (simple, same vertices and positions, weights reset to 1). */
    public Graph complement() {
        Graph g = new Graph();
        g.directed = directed;
        g.weighted = weighted;
        g.multi = false;
        for (Vertex v : vertices) {
            g.vertices.add(new Vertex(v.name, v.location.x, v.location.y));
        }
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            for (int j = directed ? 0 : i + 1; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (countBetween(vertices.get(i), vertices.get(j)) == 0) {
                    g.edges.add(new Edge(g.vertices.get(i), g.vertices.get(j), 1.0));
                }
            }
        }
        return g;
    }
}