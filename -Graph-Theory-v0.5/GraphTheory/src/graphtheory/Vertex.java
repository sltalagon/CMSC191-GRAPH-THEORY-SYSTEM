package graphtheory;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;

/**
 * A vertex of the graph. Pure model + drawing: adjacency lives in {@link Graph}
 * (edges) and {@link GraphData} (index based snapshot used by the analysers).
 *
 * @author mk
 */
public class Vertex {

    public static final int RADIUS = 20;

    public String name;
    public Point location;
    public boolean wasFocused;
    public boolean wasClicked;      // selected / being dragged from
    public boolean isCutVertex;     // cutpoint found by the analysis
    public boolean danger;          // hovered by the Remove tool (about to be deleted)
    public int role;                // 0 none, 1 = "From" of the Pair explorer, 2 = "To"

    public Vertex(String name, int x, int y) {
        this.name = name;
        location = new Point(x, y);
    }

    public boolean hasIntersection(int x, int y) {
        return location.distanceSq(x, y) <= (double) RADIUS * RADIUS;
    }

    public void draw(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        int r = RADIUS;
        int inner = 15;
        Color ring = new Color(0x3B4252);
        Color fill = Color.WHITE;
        if (isCutVertex) {
            ring = Ui.WARN;
            fill = new Color(0xFFEFD9);
        }
        if (role == 1) {
            ring = Ui.GOOD;
        } else if (role == 2) {
            ring = Ui.PURPLE;
        }
        if (wasFocused) {
            ring = Ui.ACCENT_SOFT;
        }
        if (wasClicked) {
            ring = Ui.ACCENT;
            g2.setColor(new Color(0x2D, 0x6C, 0xDF, 60));      // selection halo
            g2.fillOval(location.x - r - 6, location.y - r - 6, 2 * r + 12, 2 * r + 12);
        }
        if (danger) {
            ring = Ui.DANGER;
        }

        g2.setColor(new Color(0, 0, 0, 26));                    // soft shadow
        g2.fillOval(location.x - r + 1, location.y - r + 3, 2 * r, 2 * r);
        g2.setColor(ring);
        g2.fillOval(location.x - r, location.y - r, 2 * r, 2 * r);
        g2.setColor(fill);
        g2.fillOval(location.x - inner, location.y - inner, 2 * inner, 2 * inner);

        Font orig = g2.getFont();
        g2.setFont(orig.deriveFont(Font.BOLD, 13f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(Ui.INK);
        g2.drawString(name, location.x - fm.stringWidth(name) / 2,
                location.y + (fm.getAscent() - fm.getDescent()) / 2);
        g2.setFont(orig);
    }
}