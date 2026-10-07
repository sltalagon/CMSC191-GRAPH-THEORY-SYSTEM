/*
 * Saves and loads graphs.
 *
 * New format (text):
 *   GRAPH 2
 *   directed true|false
 *   weighted true|false
 *   multi true|false
 *   vertices N
 *   name x y            (N lines, names contain no spaces)
 *   edges M
 *   from to weight      (M lines, 0-based vertex indices)
 *
 * The original format (vertex count, names, 0/1 adjacency matrix, "x,y" lines)
 * can still be opened; it is read as an undirected, unweighted simple graph.
 */
package graphtheory;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.StringTokenizer;
import java.util.Vector;
import javax.swing.JFileChooser;

/**
 * @author mk
 */
public class FileManager {

    public JFileChooser jF;

    public FileManager() {
        jF = new JFileChooser();
    }

    public boolean saveFile(Graph g, File f) {
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(f));
            out.write("GRAPH 2");
            out.newLine();
            out.write("directed " + g.directed);
            out.newLine();
            out.write("weighted " + g.weighted);
            out.newLine();
            out.write("multi " + g.multi);
            out.newLine();
            out.write("vertices " + g.vertices.size());
            out.newLine();
            for (Vertex v : g.vertices) {
                out.write(v.name.replaceAll("\\s+", "_") + " " + v.location.x + " " + v.location.y);
                out.newLine();
            }
            java.util.Map<Vertex, Integer> idx = g.indexMap();
            out.write("edges " + g.edges.size());
            out.newLine();
            for (Edge e : g.edges) {
                out.write(idx.get(e.vertex1) + " " + idx.get(e.vertex2) + " " + Double.toString(e.weight));
                out.newLine();
            }
            return true;
        } catch (IOException e) {
            System.out.println(e);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignore) {
                    // nothing more to do
                }
            }
        }
    }

    /** Returns null if the file can't be read or isn't a valid graph file. */
    public Graph loadFile(File f) {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(f));
            Vector<String> lines = new Vector<String>();
            String s;
            while ((s = in.readLine()) != null) {
                lines.add(s);
            }
            while (!lines.isEmpty() && lines.lastElement().trim().isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            if (lines.isEmpty()) {
                return null;
            }
            if (lines.get(0).trim().startsWith("GRAPH")) {
                return loadNew(lines);
            }
            return loadLegacy(lines);
        } catch (Exception e) {
            System.out.println(e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // nothing more to do
                }
            }
        }
    }

    private Graph loadNew(Vector<String> lines) {
        Graph g = new Graph();
        int p = 1;
        g.directed = flag(lines.get(p++), "directed");
        g.weighted = flag(lines.get(p++), "weighted");
        g.multi = flag(lines.get(p++), "multi");
        int n = count(lines.get(p++), "vertices");
        for (int i = 0; i < n; i++) {
            StringTokenizer st = new StringTokenizer(lines.get(p++));
            String name = st.nextToken();
            int x = Integer.parseInt(st.nextToken());
            int y = Integer.parseInt(st.nextToken());
            g.vertices.add(new Vertex(name, x, y));
        }
        int m = count(lines.get(p++), "edges");
        for (int i = 0; i < m; i++) {
            StringTokenizer st = new StringTokenizer(lines.get(p++));
            int a = Integer.parseInt(st.nextToken());
            int b = Integer.parseInt(st.nextToken());
            double w = Double.parseDouble(st.nextToken());
            if (a < 0 || b < 0 || a >= n || b >= n || !(w > 0) || Double.isInfinite(w)) {
                return null;
            }
            g.addEdge(g.vertices.get(a), g.vertices.get(b), w);
        }
        return g;
    }

    private boolean flag(String line, String key) {
        StringTokenizer st = new StringTokenizer(line);
        if (!st.nextToken().equals(key)) {
            throw new IllegalArgumentException("expected " + key);
        }
        return Boolean.parseBoolean(st.nextToken());
    }

    private int count(String line, String key) {
        StringTokenizer st = new StringTokenizer(line);
        if (!st.nextToken().equals(key)) {
            throw new IllegalArgumentException("expected " + key);
        }
        int c = Integer.parseInt(st.nextToken());
        if (c < 0) {
            throw new IllegalArgumentException("negative count");
        }
        return c;
    }

    private Graph loadLegacy(Vector<String> lines) {
        Graph g = new Graph();
        int p = 0;
        int size = Integer.parseInt(lines.get(p++).trim());
        for (int i = 0; i < size; i++) {
            g.vertices.add(new Vertex(lines.get(p++).trim(), 0, 0));
        }
        for (int j = 0; j < size; j++) {
            String row = lines.get(p++);
            for (int l = j + 1; l < size; l++) {
                if (row.charAt(l) == '1') {
                    g.addEdge(g.vertices.get(j), g.vertices.get(l), 1.0);
                }
            }
        }
        for (int i = 0; i < size && p < lines.size(); i++) {
            String[] xy = lines.get(p++).split(",");
            g.vertices.get(i).location = new Point(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
        }
        return g;
    }

    /** Parses a user-typed weight; returns NaN if it is not a positive finite number. */
    public static double parseWeight(String s) {
        try {
            double w = Double.parseDouble(s.trim().replace(',', '.'));
            if (w > 0 && !Double.isInfinite(w) && !Double.isNaN(w)) {
                return w;
            }
        } catch (NumberFormatException ex) {
            // fall through
        }
        return Double.NaN;
    }
}