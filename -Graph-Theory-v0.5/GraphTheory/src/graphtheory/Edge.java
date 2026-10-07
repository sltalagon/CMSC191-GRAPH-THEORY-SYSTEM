package graphtheory;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * An edge. {@code vertex1 -> vertex2} is the direction when the graph is
 * directed (ignored otherwise). Supports a weight, self-loops and parallel
 * edges (parallel edges are drawn as separate curves).
 *
 * @author mk
 */
public class Edge {

    public Vertex vertex1;          // tail
    public Vertex vertex2;          // head
    public double weight = 1.0;
    public boolean wasFocused;
    public boolean wasClicked;
    public boolean danger;          // hovered by the Remove tool (about to be deleted)
    public boolean isBridge;        // found by the analysis
    public boolean onPath;          // part of the geodesic shown in the Pair explorer

    // ---- layout, filled in by Graph.layoutEdges() / layout() ----
    public int slot;                // position inside a group of parallel edges
    public int slotCount = 1;
    public int loopIndex;           // n-th loop on the same vertex
    public boolean flip;            // edge runs against the group's canonical direction

    private static final double HIT_TOLERANCE = 6.0;
    private static final double GAP = 26.0;             // spacing between parallel edges
    private static final int SAMPLES = 36;

    private double[] px = new double[0];
    private double[] py = new double[0];
    private double labelX, labelY;
    private double dirX = 1, dirY = 0;                  // direction at the arrow tip
    private boolean hasGeometry;

    public Edge(Vertex v1, Vertex v2) {
        vertex1 = v1;
        vertex2 = v2;
    }

    public Edge(Vertex v1, Vertex v2, double w) {
        this(v1, v2);
        weight = w;
    }

    public boolean isLoop() {
        return vertex1 == vertex2;
    }

    // ------------------------------------------------------------------
    // Geometry (cubic Bezier for everything: straight, bowed and loops)
    // ------------------------------------------------------------------
    private static double bez(double t, double a, double b, double c, double d) {
        double u = 1 - t;
        return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
    }

    private static double bezDeriv(double t, double a, double b, double c, double d) {
        double u = 1 - t;
        return 3 * u * u * (b - a) + 6 * u * t * (c - b) + 3 * t * t * (d - c);
    }

    public void layout() {
        final double R = Vertex.RADIUS;
        double x1 = vertex1.location.x, y1 = vertex1.location.y;
        double x2 = vertex2.location.x, y2 = vertex2.location.y;
        double[] bx = new double[4];
        double[] by = new double[4];
        double t0 = 0, t1 = 1;

        if (isLoop()) {
            double a = Math.toRadians(-90 + loopIndex * 75);
            double reach = R + 54;
            bx[0] = x1 + R * Math.cos(a - 0.55);
            by[0] = y1 + R * Math.sin(a - 0.55);
            bx[1] = x1 + reach * Math.cos(a - 0.5);
            by[1] = y1 + reach * Math.sin(a - 0.5);
            bx[2] = x1 + reach * Math.cos(a + 0.5);
            by[2] = y1 + reach * Math.sin(a + 0.5);
            bx[3] = x1 + R * Math.cos(a + 0.55);
            by[3] = y1 + R * Math.sin(a + 0.55);
        } else {
            double dx = x2 - x1, dy = y2 - y1;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) {
                hasGeometry = false;
                return;
            }
            double nx = -dy / len, ny = dx / len;
            double off = (slot - (slotCount - 1) / 2.0) * GAP * (flip ? -1 : 1);
            bx[0] = x1;
            by[0] = y1;
            bx[3] = x2;
            by[3] = y2;
            bx[1] = x1 + dx / 3 + nx * off * 4 / 3;
            by[1] = y1 + dy / 3 + ny * off * 4 / 3;
            bx[2] = x1 + 2 * dx / 3 + nx * off * 4 / 3;
            by[2] = y1 + 2 * dy / 3 + ny * off * 4 / 3;

            // trim the curve so it starts/ends on the vertex rings
            if (Math.hypot(bez(0.5, bx[0], bx[1], bx[2], bx[3]) - x1, bez(0.5, by[0], by[1], by[2], by[3]) - y1) < R
                    || Math.hypot(bez(0.5, bx[0], bx[1], bx[2], bx[3]) - x2, bez(0.5, by[0], by[1], by[2], by[3]) - y2) < R) {
                hasGeometry = false;        // vertices overlap
                return;
            }
            double lo = 0, hi = 0.5;
            for (int i = 0; i < 30; i++) {
                double mid = (lo + hi) / 2;
                if (Math.hypot(bez(mid, bx[0], bx[1], bx[2], bx[3]) - x1, bez(mid, by[0], by[1], by[2], by[3]) - y1) >= R) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            t0 = hi;
            lo = 0.5;
            hi = 1;
            for (int i = 0; i < 30; i++) {
                double mid = (lo + hi) / 2;
                if (Math.hypot(bez(mid, bx[0], bx[1], bx[2], bx[3]) - x2, bez(mid, by[0], by[1], by[2], by[3]) - y2) >= R) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            t1 = lo;
        }

        px = new double[SAMPLES + 1];
        py = new double[SAMPLES + 1];
        for (int i = 0; i <= SAMPLES; i++) {
            double t = t0 + (t1 - t0) * i / SAMPLES;
            px[i] = bez(t, bx[0], bx[1], bx[2], bx[3]);
            py[i] = bez(t, by[0], by[1], by[2], by[3]);
        }
        double tm = (t0 + t1) / 2;
        labelX = bez(tm, bx[0], bx[1], bx[2], bx[3]);
        labelY = bez(tm, by[0], by[1], by[2], by[3]);
        double ddx = bezDeriv(t1, bx[0], bx[1], bx[2], bx[3]);
        double ddy = bezDeriv(t1, by[0], by[1], by[2], by[3]);
        double dl = Math.hypot(ddx, ddy);
        if (dl > 1e-9) {
            dirX = ddx / dl;
            dirY = ddy / dl;
        }
        hasGeometry = true;
    }

    // Distance from the point to the drawn curve (the old slope-based test
    // broke on steep/vertical edges; this works for any shape).
    public boolean hasIntersection(int x, int y) {
        if (!hasGeometry) {
            return false;
        }
        for (int i = 0; i + 1 < px.length; i++) {
            if (segDist(x, y, px[i], py[i], px[i + 1], py[i + 1]) <= HIT_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private static double segDist(double x, double y, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        double t = (len2 == 0) ? 0 : ((x - x1) * dx + (y - y1) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy));
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------
    private Color colour(boolean highlightBridges) {
        if (danger) {
            return Ui.DANGER;
        }
        if (wasClicked) {
            return Ui.ACCENT;
        }
        if (onPath) {
            return Ui.TEAL;
        }
        if (wasFocused) {
            return Ui.ACCENT_SOFT;
        }
        if (isBridge && highlightBridges) {
            return Ui.WARN;
        }
        return new Color(0x5B6275);
    }

    private float width(boolean highlightBridges) {
        if (danger) {
            return 3f;
        }
        if (wasClicked) {
            return 3.5f;
        }
        if (onPath) {
            return 4f;
        }
        if (wasFocused) {
            return 3f;
        }
        if (isBridge && highlightBridges) {
            return 2.6f;
        }
        return 1.8f;
    }

    public void draw(Graphics2D g2, boolean directed, boolean highlightBridges) {
        if (!hasGeometry) {
            return;
        }
        Stroke old = g2.getStroke();
        Color c = colour(highlightBridges);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(px[0], py[0]);
        for (int i = 1; i < px.length; i++) {
            path.lineTo(px[i], py[i]);
        }
        g2.setColor(c);
        g2.setStroke(new BasicStroke(width(highlightBridges), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(path);
        g2.setStroke(old);

        if (directed) {
            double tx = px[px.length - 1], ty = py[py.length - 1];
            double len = 13, half = 5.5;
            double bx = tx - dirX * len, by = ty - dirY * len;
            double nx = -dirY, ny = dirX;
            Path2D.Double head = new Path2D.Double();
            head.moveTo(tx, ty);
            head.lineTo(bx + nx * half, by + ny * half);
            head.lineTo(bx - nx * half, by - ny * half);
            head.closePath();
            g2.fill(head);
        }
    }

    /** Weight pill drawn on top of the edge (call after all edges are drawn). */
    public void drawLabel(Graphics2D g2, boolean highlightBridges) {
        if (!hasGeometry) {
            return;
        }
        String s = Ui.fmt(weight);
        Font orig = g2.getFont();
        g2.setFont(orig.deriveFont(Font.BOLD, 11f));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(s) + 10, h = fm.getHeight() - 2;
        double x = labelX - w / 2.0, y = labelY - h / 2.0;
        Color c = colour(highlightBridges);
        g2.setColor(Color.WHITE);
        g2.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(new RoundRectangle2D.Double(x, y, w, h, h, h));
        g2.setColor(Ui.INK);
        g2.drawString(s, (float) (labelX - fm.stringWidth(s) / 2.0), (float) (labelY + (fm.getAscent() - fm.getDescent()) / 2.0));
        g2.setFont(orig);
    }
}