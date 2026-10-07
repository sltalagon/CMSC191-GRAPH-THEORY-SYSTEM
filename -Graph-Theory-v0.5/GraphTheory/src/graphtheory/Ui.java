package graphtheory;

import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

/**
 * Colours, fonts and small formatting helpers shared by every part of the UI,
 * so the whole application uses one consistent visual language.
 */
public final class Ui {

    private Ui() {
    }

    public static final Color INK = new Color(0x1F2430);
    public static final Color MUTED = new Color(0x6B7280);
    public static final Color FAINT = new Color(0x9AA0AA);
    public static final Color ACCENT = new Color(0x2D6CDF);
    public static final Color ACCENT_SOFT = new Color(0x6FA0F0);
    public static final Color DANGER = new Color(0xD93025);
    public static final Color WARN = new Color(0xE67E22);
    public static final Color GOOD = new Color(0x1E8E3E);
    public static final Color TEAL = new Color(0x0F9D9D);
    public static final Color PURPLE = new Color(0x7B3FE4);
    public static final Color SURFACE = new Color(0xF6F7F9);
    public static final Color BORDER = new Color(0xE2E4E8);
    public static final Color GRID = new Color(0xE9ECF1);

    // Property-group colours (same grouping as the property checklist:
    // green = basics, orange = connectivity, purple = families, yellow = traversability).
    public static final int GROUP_BASIC = 0x2E9E4F;
    public static final int GROUP_CONNECT = 0xE67E22;
    public static final int GROUP_FAMILY = 0x8E44AD;
    public static final int GROUP_TRAVERSE = 0xC9A100;
    public static final int GROUP_OTHER = 0x5B6275;
    public static final int GROUP_PAIR = 0x2D6CDF;

    public static Font font(int style, float size) {
        return new Font("Segoe UI", style, Math.round(size));
    }

    /** Pale tint of a colour, used for section backgrounds. */
    public static Color tint(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = Math.round(r + (255 - r) * amount);
        g = Math.round(g + (255 - g) * amount);
        b = Math.round(b + (255 - b) * amount);
        return new Color(r, g, b);
    }

    /** 3 -> "3", 2.5 -> "2.5", 0.333333 -> "0.333", infinity -> "\u221E". */
    public static String fmt(double w) {
        if (Double.isInfinite(w) || Double.isNaN(w)) {
            return "\u221E";
        }
        if (w == Math.rint(w) && Math.abs(w) < 1e12) {
            return String.valueOf((long) w);
        }
        String s = String.format(Locale.US, "%.3f", w);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}