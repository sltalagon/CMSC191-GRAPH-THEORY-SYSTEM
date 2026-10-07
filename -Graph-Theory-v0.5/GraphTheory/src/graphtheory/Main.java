package graphtheory;

import java.awt.Color;
import java.awt.Font;
import java.util.Date;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

public class Main {

    public static void main(String[] args) throws Exception {

        setFlatLookAndFeel();

        Date date = new Date();
        new Canvas("Graphite " + date.toString(), 800, 600, Color.WHITE);

    }

    // Metal fully respects a custom theme (unlike Nimbus, which mostly ignores
    // UIManager color keys), so this gives a consistent flat, modern look
    // across every Swing component without needing an external library.
    private static void setFlatLookAndFeel() {
        try {
            MetalLookAndFeel.setCurrentTheme(new FlatModernTheme());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ex) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex2) {
                // keep Swing's default if even that fails
            }
        }
    }

    private static class FlatModernTheme extends DefaultMetalTheme {

        private final ColorUIResource accent = new ColorUIResource(0x2D6CDF);
        private final ColorUIResource accentLight = new ColorUIResource(0x6FA0F0);
        private final ColorUIResource accentPale = new ColorUIResource(0xBFD6FA);
        private final ColorUIResource neutralDark = new ColorUIResource(0x8A8A8A);
        private final ColorUIResource neutralMid = new ColorUIResource(0xE2E2E2);
        private final ColorUIResource neutralLight = new ColorUIResource(0xF6F7F9);
        private final ColorUIResource text = new ColorUIResource(0x1F2430);

        public String getName() {
            return "FlatModern";
        }

        protected ColorUIResource getPrimary1() {
            return accent;
        }

        protected ColorUIResource getPrimary2() {
            return accentLight;
        }

        protected ColorUIResource getPrimary3() {
            return accentPale;
        }

        protected ColorUIResource getSecondary1() {
            return neutralDark;
        }

        protected ColorUIResource getSecondary2() {
            return neutralMid;
        }

        protected ColorUIResource getSecondary3() {
            return neutralLight;
        }

        public ColorUIResource getControlTextColor() {
            return text;
        }

        public ColorUIResource getSystemTextColor() {
            return text;
        }

        public ColorUIResource getUserTextColor() {
            return text;
        }

        public ColorUIResource getMenuForeground() {
            return text;
        }

        public FontUIResource getControlTextFont() {
            return new FontUIResource("Segoe UI", Font.PLAIN, 12);
        }

        public FontUIResource getSystemTextFont() {
            return getControlTextFont();
        }

        public FontUIResource getUserTextFont() {
            return getControlTextFont();
        }

        public FontUIResource getMenuTextFont() {
            return getControlTextFont();
        }

        public FontUIResource getSubTextFont() {
            return new FontUIResource("Segoe UI", Font.PLAIN, 11);
        }

        public FontUIResource getWindowTitleFont() {
            return new FontUIResource("Segoe UI Semibold", Font.BOLD, 13);
        }
    }
}