package graphtheory;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.LinkedList;
import java.util.Vector;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.plaf.metal.MetalTabbedPaneUI;
import javax.swing.table.JTableHeader;

/**
 * Main window: the drawing canvas on the left, the analysis tabs on the right.
 * The editable model is a {@link Graph}; all analysis runs on an immutable
 * {@link GraphData} snapshot on a background thread.
 */
public class Canvas {

    public JFrame frame;
    public int width, height;

    private static final int TOOL_VERTEX = 1, TOOL_EDGE = 2, TOOL_MOVE = 3, TOOL_REMOVE = 4;
    private static final int M_DIR = 0, M_WEIGHT = 1, M_MULTI = 2;
    private static final String[] MODE_LABELS = {"Directed", "Weighted", "Parallel edges & loops"};
    private static final String[] MODE_TIPS = {
        "Edges become arcs (drag from tail to head)",
        "Edges carry a positive weight, used as their length",
        "Allow several edges between the same two vertices, and self-loops"};
    private static final int RIGHT_PANEL_WIDTH = 540;
    private static final int VERTEX_RADIUS = Vertex.RADIUS;
    private static final int MAX_UNDO = 100;

    private final String baseTitle;
    private final Color backgroundColour;
    private final FileManager fileManager = new FileManager();
    private File currentFile;
    private boolean dirty;
    private int selectedTool = TOOL_VERTEX;

    // ---- UI chrome ----
    private CanvasPane canvas;
    private JToggleButton[] toolBtns = new JToggleButton[5];
    private JRadioButtonMenuItem[] toolMenuItems = new JRadioButtonMenuItem[5];
    private JCheckBox[] modeBoxes = new JCheckBox[3];
    private JCheckBoxMenuItem[] modeItems = new JCheckBoxMenuItem[3];
    private JLabel statusLabel, hintLabel;
    private JProgressBar busyBar;
    private JTabbedPane rightTabs;
    private int tabsMinWidth;
    private JButton undoBtn, redoBtn;
    private javax.swing.Timer msgTimer;
    private String message;

    // ---- Tabs ----
    private JPanel overviewPanel, matricesPanel;
    private JTable verticesTable;
    private JLabel containerStatus, containerDiameters;
    private JButton containerBtn;
    private JTable containerTable;
    private JTextArea containerDetail;
    @SuppressWarnings("rawtypes")
    private JComboBox pairFrom, pairTo;
    private boolean updatingCombos;
    private JEditorPane pairView;

    // ---- Degree Distribution Components ----
    private JPanel degreeDistPanel;
    private JLabel degreeStatsLabel;
    private DegreeChartPanel degreeChartPanel;
    private JTable degreeTable;
    private DefaultTableModel degreeTableModel;
    private JRadioButton radTotalDeg, radInDeg, radOutDeg;
    private ButtonGroup degTypeGroup;
    private JPanel degTypePanel;

    // ---- Actions ----
    private Action[] toolActs = new Action[5];
    private Action undoAct, redoAct, deleteAct, clearAct, arrangeAct, complementAct, compareAct;

    // ---- Graph model ----
    private Graph graph = new Graph();

    // ---- Analysis results currently on display ----
    private GraphAnalyzer gA = new GraphAnalyzer();
    private GraphData curData;
    private int[][] adjM;
    private double[][] weightM, distM;
    private GraphProperties.ContainerResult containerResult;
    private VertexPair pairResult;
    private int graphVersion = 0;       // bumped on every structural change
    private int analyzedVersion = -1;   // version the displayed results belong to
    private boolean analyzing = false;

    // ---- Interaction state ----
    private Vertex selV, hoverV, edgeFrom, dragV;
    private Edge selE, hoverE;
    private Point cursorPt;
    private int dragDX, dragDY;
    private boolean dragMoved, edgeLeft;
    private Graph preDrag;

    // ---- Undo / redo ----
    private LinkedList<Graph> undoStack = new LinkedList<Graph>();
    private LinkedList<Graph> redoStack = new LinkedList<Graph>();

    public Canvas(String title, int width, int height, Color bgColour) {
        this.baseTitle = title;
        this.width = width;
        this.height = height;
        this.backgroundColour = bgColour;

        frame = new JFrame();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });

        fileManager.jF.setFileFilter(new FileNameExtensionFilter("Graph files (*.txt)", "txt"));

        msgTimer = new javax.swing.Timer(3500, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                message = null;
                updateStatusBar();
            }
        });
        msgTimer.setRepeats(false);

        createActions();

        canvas = new CanvasPane();
        InputListener inputListener = new InputListener();
        canvas.addMouseListener(inputListener);
        canvas.addMouseMotionListener(inputListener);
        canvas.setPreferredSize(new Dimension(width, height));
        canvas.setMinimumSize(new Dimension(300, 300));
        canvas.setBorder(BorderFactory.createLineBorder(Ui.BORDER));

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(buildToolBar(), BorderLayout.NORTH);
        leftPanel.add(canvas, BorderLayout.CENTER);
        leftPanel.add(buildStatusBar(), BorderLayout.SOUTH);
        leftPanel.setMinimumSize(new Dimension(560, 320));

        UIManager.put("TabbedPane.tabInsets", new Insets(6, 10, 6, 10));
        UIManager.put("TabbedPane.tabAreaInsets", new Insets(4, 0, 0, 0));
        UIManager.put("Table.focusCellHighlightBorder", BorderFactory.createEmptyBorder());
        rightTabs = new JTabbedPane();
        rightTabs.setUI(new StretchTabsUI());
        rightTabs.setFont(Ui.font(Font.PLAIN, 12));
        rightTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        rightTabs.addTab("Overview", scroll(buildOverviewTab()));
        rightTabs.addTab("Vertices", buildVerticesTab());
        rightTabs.addTab("Matrices", scroll(buildMatricesTab()));
        rightTabs.addTab("Containers", buildContainersTab());
        rightTabs.addTab("Pair", buildPairTab());
        rightTabs.addTab("Degrees", buildDegreeDistributionTab());
        // The panel can never be dragged narrower than its tab strip, so the tiny
        // scroll arrows never appear and every tab is always visible and clickable.
        FontMetrics tabFm = rightTabs.getFontMetrics(rightTabs.getFont());
        int tabsNeed = 30;
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            tabsNeed += tabFm.stringWidth(rightTabs.getTitleAt(i)) + 32;
            rightTabs.setToolTipTextAt(i, rightTabs.getTitleAt(i) + "  (Ctrl+" + (i + 1) + ")");
        }
        tabsMinWidth = tabsNeed;
        rightTabs.setPreferredSize(new Dimension(Math.max(RIGHT_PANEL_WIDTH, tabsNeed + 40), height));
        rightTabs.setMinimumSize(new Dimension(tabsNeed, 200));
        rightTabs.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                applyHighlights();
                canvas.repaint();
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightTabs);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setBorder(null);

        frame.setContentPane(split);
        frame.setJMenuBar(buildMenuBar());
        installKeyBindings();

        frame.pack();
        frame.setMinimumSize(new Dimension(Math.max(920, 560 + tabsMinWidth + 16), 520));
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(frame.getWidth(), usable.width), Math.min(frame.getHeight(), usable.height));
        frame.setLocationRelativeTo(null);

        selectTool(TOOL_VERTEX);
        syncModeControls();
        refreshPanels();
        updateTitle();
        updateActions();
        updateStatusBar();
        frame.setVisible(true);
    }

    private JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.getViewport().setBackground(Color.WHITE);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getHorizontalScrollBar().setUnitIncrement(16);
        sp.setBorder(null);
        return sp;
    }

    /** "Graphite" wordmark: a small pencil followed by the name. Purely decorative. */
    private static class Wordmark extends JComponent {

        private static final String NAME = "Graphite";

        Wordmark() {
            setFont(Ui.font(Font.BOLD, 15));
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(10 + 20 + 7 + fm.stringWidth(NAME) + 18, 26);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int cy = getHeight() / 2;
            // pencil, drawn pointing down-left: eraser, ferrule, body, wood cone, graphite tip
            java.awt.geom.AffineTransform keep = g2.getTransform();
            g2.translate(20, cy);
            g2.rotate(Math.toRadians(135));
            g2.scale(1.15, 1.15);
            g2.setColor(new Color(0xF2A7A7));
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(-9, -2.6, 3, 5.2, 1.6, 1.6));
            g2.setColor(Ui.FAINT);
            g2.fill(new java.awt.geom.Rectangle2D.Double(-6.2, -2.6, 1.6, 5.2));
            g2.setColor(Ui.ACCENT);
            g2.fill(new java.awt.geom.Rectangle2D.Double(-4.6, -2.6, 8.6, 5.2));
            g2.setColor(new Color(0xF2D3A5));
            java.awt.geom.Path2D.Double wood = new java.awt.geom.Path2D.Double();
            wood.moveTo(4, -2.6);
            wood.lineTo(9, 0);
            wood.lineTo(4, 2.6);
            wood.closePath();
            g2.fill(wood);
            g2.setColor(Ui.INK);
            java.awt.geom.Path2D.Double lead = new java.awt.geom.Path2D.Double();
            lead.moveTo(7.2, -0.95);
            lead.lineTo(9, 0);
            lead.lineTo(7.2, 0.95);
            lead.closePath();
            g2.fill(lead);
            g2.setTransform(keep);
            FontMetrics fm = g2.getFontMetrics();
            g2.setFont(getFont());
            fm = g2.getFontMetrics();
            g2.setColor(Ui.INK);
            g2.drawString(NAME, 10 + 20 + 7, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Tab strip whose tabs share all the free width equally, so no space is wasted beside them. */
    private static class StretchTabsUI extends MetalTabbedPaneUI {

        @Override
        protected int calculateTabWidth(int tabPlacement, int tabIndex, FontMetrics metrics) {
            int w = super.calculateTabWidth(tabPlacement, tabIndex, metrics);
            int count = tabPane.getTabCount();
            if (tabPlacement != JTabbedPane.TOP && tabPlacement != JTabbedPane.BOTTOM || count == 0) {
                return w;
            }
            int total = 0;
            for (int i = 0; i < count; i++) {
                total += super.calculateTabWidth(tabPlacement, i, metrics);
            }
            Insets in = tabPane.getInsets();
            int avail = tabPane.getWidth() - in.left - in.right - tabAreaInsets.left - tabAreaInsets.right - 6;
            int free = avail - total;
            if (free <= 0) {
                return w;
            }
            int each = free / count;
            return w + each + (tabIndex == count - 1 ? free - each * count : 0);
        }
    }

    /** Panel that stretches to the viewport width, and only scrolls sideways when its content is wider. */
    private static class TrackingPanel extends JPanel implements Scrollable {

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 16;
        }

        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return o == SwingConstants.VERTICAL ? r.height - 16 : r.width - 16;
        }

        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport && getParent().getWidth() >= getPreferredSize().width;
        }

        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() >= getPreferredSize().height;
        }
    }

    // ==================================================================
    // Custom Degree Distribution Bar Chart Panel
    // ==================================================================
    public static class DegreeChartPanel extends JPanel {
        private int[] counts = new int[0];
        private boolean isDirected = false;
        private String chartTitle = "Degree Distribution";

        public DegreeChartPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(380, 210));
            setMinimumSize(new Dimension(250, 160));
        }

        public void setData(int[] counts, boolean isDirected, String chartTitle) {
            this.counts = (counts != null) ? counts : new int[0];
            this.isDirected = isDirected;
            this.chartTitle = chartTitle;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            int marginLeft = 45;
            int marginRight = 20;
            int marginTop = 30;
            int marginBottom = 40;

            int plotW = w - marginLeft - marginRight;
            int plotH = h - marginTop - marginBottom;

            // Title
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2.setColor(new Color(0x1F2937));
            FontMetrics fmTitle = g2.getFontMetrics();
            g2.drawString(chartTitle, (w - fmTitle.stringWidth(chartTitle)) / 2, 18);

            if (counts.length == 0 || plotW <= 0 || plotH <= 0) {
                g2.setFont(new Font("SansSerif", Font.ITALIC, 12));
                g2.setColor(Color.GRAY);
                String msg = "No graph data available";
                g2.drawString(msg, (w - g2.getFontMetrics().stringWidth(msg)) / 2, h / 2);
                g2.dispose();
                return;
            }

            int maxFreq = 0;
            for (int c : counts) {
                if (c > maxFreq) maxFreq = c;
            }
            if (maxFreq == 0) maxFreq = 1;

            // Y-axis grid lines & ticks
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            FontMetrics fmSmall = g2.getFontMetrics();
            int yTicks = Math.min(maxFreq, 5);
            for (int i = 0; i <= yTicks; i++) {
                int val = Math.round((float) maxFreq * i / yTicks);
                int y = marginTop + plotH - (int) ((double) val / maxFreq * plotH);

                g2.setColor(new Color(0xE5E7EB));
                g2.drawLine(marginLeft, y, marginLeft + plotW, y);

                g2.setColor(new Color(0x6B7280));
                String label = String.valueOf(val);
                g2.drawString(label, marginLeft - fmSmall.stringWidth(label) - 6, y + 4);
            }

            // Draw Axes
            g2.setColor(new Color(0x9CA3AF));
            g2.drawLine(marginLeft, marginTop, marginLeft, marginTop + plotH);
            g2.drawLine(marginLeft, marginTop + plotH, marginLeft + plotW, marginTop + plotH);

            // Draw Bars
            int numBars = counts.length;
            double barWidthSpace = (double) plotW / numBars;
            int gap = Math.max(2, (int) (barWidthSpace * 0.25));
            int barWidth = Math.max(1, (int) barWidthSpace - gap);

            Color barColor = new Color(0x3B82F6);
            Color barBorder = new Color(0x1D4ED8);
            Color barTextColor = new Color(0x1E40AF);

            for (int k = 0; k < numBars; k++) {
                int count = counts[k];
                int barH = (int) ((double) count / maxFreq * plotH);
                int x = marginLeft + (int) (k * barWidthSpace) + gap / 2;
                int y = marginTop + plotH - barH;

                if (barH > 0) {
                    g2.setColor(barColor);
                    g2.fillRect(x, y, barWidth, barH);

                    g2.setColor(barBorder);
                    g2.drawRect(x, y, barWidth, barH);

                    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
                    g2.setColor(barTextColor);
                    String valStr = String.valueOf(count);
                    int strW = g2.getFontMetrics().stringWidth(valStr);
                    g2.drawString(valStr, x + (barWidth - strW) / 2, Math.max(marginTop + 10, y - 3));
                }

                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.setColor(new Color(0x374151));
                String kStr = String.valueOf(k);
                int kWidth = fmSmall.stringWidth(kStr);
                g2.drawString(kStr, x + (barWidth - kWidth) / 2, marginTop + plotH + 14);
            }

            g2.setFont(new Font("SansSerif", Font.BOLD, 10));
            g2.setColor(new Color(0x4B5563));
            String xTitle = "Degree (k)";
            g2.drawString(xTitle, marginLeft + (plotW - fmSmall.stringWidth(xTitle)) / 2, marginTop + plotH + 30);
            g2.drawString("Freq.", 6, marginTop - 10);

            g2.dispose();
        }
    }

    // ==================================================================
    // Degree Distribution Tab Builder & Updater
    // ==================================================================
    private JPanel buildDegreeDistributionTab() {
        degreeDistPanel = new JPanel(new BorderLayout(8, 8));
        degreeDistPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        degreeDistPanel.setBackground(Color.WHITE);

        JPanel topBox = new JPanel();
        topBox.setLayout(new BoxLayout(topBox, BoxLayout.Y_AXIS));
        topBox.setOpaque(false);

        degreeStatsLabel = new JLabel("Degree Distribution Statistics");
        degreeStatsLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        degreeStatsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topBox.add(degreeStatsLabel);
        topBox.add(Box.createVerticalStrut(6));

        degTypePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        degTypePanel.setOpaque(false);
        degTypePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        radTotalDeg = new JRadioButton("Total Degree", true);
        radInDeg = new JRadioButton("In-Degree");
        radOutDeg = new JRadioButton("Out-Degree");
        radTotalDeg.setFocusable(false);
        radInDeg.setFocusable(false);
        radOutDeg.setFocusable(false);

        degTypeGroup = new ButtonGroup();
        degTypeGroup.add(radTotalDeg);
        degTypeGroup.add(radInDeg);
        degTypeGroup.add(radOutDeg);

        ActionListener typeListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateDegreeDistributionTab();
            }
        };
        radTotalDeg.addActionListener(typeListener);
        radInDeg.addActionListener(typeListener);
        radOutDeg.addActionListener(typeListener);

        degTypePanel.add(radTotalDeg);
        degTypePanel.add(radInDeg);
        degTypePanel.add(radOutDeg);
        degTypePanel.setVisible(false);

        topBox.add(degTypePanel);

        degreeChartPanel = new DegreeChartPanel();

        String[] cols = {"Degree (k)", "Frequency", "Percentage", "Vertices"};
        degreeTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        degreeTable = new JTable(degreeTableModel);
        degreeTable.setRowHeight(22);
        degreeTable.getTableHeader().setReorderingAllowed(false);

        JScrollPane tableScroll = new JScrollPane(degreeTable);
        tableScroll.setPreferredSize(new Dimension(380, 140));

        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));
        centerPanel.setOpaque(false);
        centerPanel.add(degreeChartPanel, BorderLayout.CENTER);
        centerPanel.add(tableScroll, BorderLayout.SOUTH);

        degreeDistPanel.add(topBox, BorderLayout.NORTH);
        degreeDistPanel.add(centerPanel, BorderLayout.CENTER);

        return degreeDistPanel;
    }

    private void updateDegreeDistributionTab() {
        if (gA == null || gA.d == null) return;

        boolean isDirected = gA.d.directed;
        degTypePanel.setVisible(isDirected);

        int[] counts;
        String title;
        int minD, maxD;
        double avgD;

        if (isDirected && radInDeg.isSelected()) {
            counts = gA.inDegreeCounts;
            title = "In-Degree Distribution";
            minD = gA.minInDegree;
            maxD = gA.maxInDegree;
            avgD = gA.avgInDegree;
        } else if (isDirected && radOutDeg.isSelected()) {
            counts = gA.outDegreeCounts;
            title = "Out-Degree Distribution";
            minD = gA.minOutDegree;
            maxD = gA.maxOutDegree;
            avgD = gA.avgOutDegree;
        } else {
            counts = gA.degreeCounts;
            title = isDirected ? "Total Degree Distribution" : "Degree Distribution";
            minD = gA.minDegree;
            maxD = gA.maxDegree;
            avgD = gA.avgDegree;
        }

        int totalN = gA.getN();
        if (totalN == 0) {
            degreeStatsLabel.setText("Graph is empty.");
            degreeChartPanel.setData(new int[0], isDirected, title);
            degreeTableModel.setRowCount(0);
            return;
        }

        degreeStatsLabel.setText(String.format("Vertices: %d  |  Min Degree: %d  |  Max Degree: %d  |  Avg: %.2f",
                totalN, minD, maxD, avgD));

        degreeChartPanel.setData(counts, isDirected, title);

        degreeTableModel.setRowCount(0);
        if (counts != null) {
            for (int k = 0; k < counts.length; k++) {
                int freq = counts[k];
                if (freq == 0) continue;

                double pct = (double) freq / totalN * 100.0;

                StringBuilder vList = new StringBuilder();
                int vCount = 0;
                for (int v = 0; v < totalN; v++) {
                    int dVal;
                    if (isDirected && radInDeg.isSelected()) dVal = gA.d.indeg[v];
                    else if (isDirected && radOutDeg.isSelected()) dVal = gA.d.outdeg[v];
                    else dVal = gA.d.degree[v];

                    if (dVal == k) {
                        if (vCount > 0) vList.append(", ");
                        vList.append(gA.d.names[v]);
                        vCount++;
                    }
                }

                degreeTableModel.addRow(new Object[]{
                    k,
                    freq,
                    String.format("%.1f%%", pct),
                    vList.toString()
                });
            }
        }
    }

    // ==================================================================
    // Actions & Menus
    // ==================================================================
    private abstract class Act extends AbstractAction {
        Act(String name, String tip, int key, int mods) {
            super(name);
            if (tip != null) {
                putValue(SHORT_DESCRIPTION, tip);
            }
            if (key != 0) {
                putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(key, mods));
            }
        }
        public void actionPerformed(ActionEvent e) { run(); }
        abstract void run();
    }

    private void createActions() {
        final int CTRL = KeyEvent.CTRL_DOWN_MASK;
        toolActs[TOOL_VERTEX] = new Act("Add Vertex", "Add Vertex \u2014 click empty space to place one  (V or Ctrl+A)", KeyEvent.VK_A, CTRL) {
            void run() { selectTool(TOOL_VERTEX); }
        };
        toolActs[TOOL_EDGE] = new Act("Add Edge", "Add Edge \u2014 drag from one vertex to another  (E or Ctrl+E)", KeyEvent.VK_E, CTRL) {
            void run() { selectTool(TOOL_EDGE); }
        };
        toolActs[TOOL_MOVE] = new Act("Move", "Move \u2014 drag a vertex to reposition it  (M or Ctrl+G)", KeyEvent.VK_G, CTRL) {
            void run() { selectTool(TOOL_MOVE); }
        };
        toolActs[TOOL_REMOVE] = new Act("Remove", "Remove \u2014 click a vertex or edge to delete it  (R or Ctrl+R)", KeyEvent.VK_R, CTRL) {
            void run() { selectTool(TOOL_REMOVE); }
        };
        undoAct = new Act("Undo", "Undo the last change  (Ctrl+Z)", KeyEvent.VK_Z, CTRL) {
            void run() { undo(); }
        };
        redoAct = new Act("Redo", "Redo  (Ctrl+Y)", KeyEvent.VK_Y, CTRL) {
            void run() { redo(); }
        };
        deleteAct = new Act("Delete Selected", "Delete the selected vertex or edge  (Delete)", KeyEvent.VK_DELETE, 0) {
            void run() { deleteSelection(); }
        };
        clearAct = new Act("Clear All", "Remove every vertex and edge (can be undone)", 0, 0) {
            void run() { clearAll(); }
        };
        arrangeAct = new Act("Auto Arrange", "Arrange all vertices evenly in a circle", 0, 0) {
            void run() { arrangeVertices(); }
        };
        complementAct = new Act("Replace with Complement", "Replace the graph by its complement (can be undone)", 0, 0) {
            void run() { replaceWithComplement(); }
        };
        compareAct = new Act("Compare With File\u2026", "Isomorphism / subgraph tests against a saved graph", 0, 0) {
            void run() { compareWithFile(); }
        };
    }

    private JToolBar buildToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        String[] labels = {"", "Vertex", "Edge", "Move", "Remove"};
        String[] keys = {"", "V", "E", "M", "R"};
        ButtonGroup group = new ButtonGroup();
        for (int t = TOOL_VERTEX; t <= TOOL_REMOVE; t++) {
            JToggleButton b = new JToggleButton(toolActs[t]);
            b.setText(keyLabel(labels[t], keys[t], true));
            b.setFocusable(false);
            b.setMargin(new Insets(5, 12, 5, 12));
            group.add(b);
            toolBtns[t] = b;
            toolBar.add(b);
        }
        toolBar.addSeparator(new Dimension(14, 0));
        undoBtn = plainButton(undoAct);
        redoBtn = plainButton(redoAct);
        toolBar.add(undoBtn);
        toolBar.add(redoBtn);
        toolBar.addSeparator(new Dimension(14, 0));
        toolBar.add(plainButton(arrangeAct));
        toolBar.addSeparator(new Dimension(14, 0));
        String[] shortLabels = {"Directed", "Weighted", "Multi"};
        for (int m = 0; m < 3; m++) {
            final int which = m;
            JCheckBox cb = new JCheckBox(shortLabels[m]);
            cb.setToolTipText(MODE_TIPS[m]);
            cb.setFocusable(false);
            cb.setOpaque(false);
            cb.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    setMode(which, ((JCheckBox) e.getSource()).isSelected());
                }
            });
            modeBoxes[m] = cb;
            toolBar.add(cb);
        }
        return toolBar;
    }

    /** Button text with the shortcut key shown beside the name (plain text when disabled so it greys out). */
    private static String keyLabel(String name, String key, boolean enabled) {
        if (!enabled) {
            return name + "  " + key;
        }
        return "<html>" + name + "&nbsp;&nbsp;<font color='#7C8494' size='-1'>" + key + "</font></html>";
    }

    private void refreshKeyLabels() {
        if (undoBtn != null) {
            undoBtn.setText(keyLabel("Undo", "Ctrl+Z", undoAct.isEnabled()));
        }
        if (redoBtn != null) {
            redoBtn.setText(keyLabel("Redo", "Ctrl+Y", redoAct.isEnabled()));
        }
    }

    private JButton plainButton(Action a) {
        JButton b = new JButton(a);
        b.setFocusable(false);
        return b;
    }

    private JPanel buildStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout(12, 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.BORDER),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        statusBar.setBackground(Ui.SURFACE);
        statusBar.setOpaque(true);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        hintLabel = new JLabel(" ", SwingConstants.RIGHT);
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 12f));
        hintLabel.setForeground(new Color(0x555B66));
        hintLabel.setMinimumSize(new Dimension(0, 0));
        busyBar = new JProgressBar();
        busyBar.setIndeterminate(true);
        busyBar.setPreferredSize(new Dimension(90, 12));
        busyBar.setToolTipText("Analysing the graph\u2026");
        busyBar.setVisible(false);

        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(hintLabel, BorderLayout.CENTER);
        statusBar.add(busyBar, BorderLayout.EAST);
        return statusBar;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        final int CTRL = KeyEvent.CTRL_DOWN_MASK;

        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        file.add(new Act("New", null, KeyEvent.VK_N, CTRL) { void run() { newGraph(); } });
        file.add(new Act("Open\u2026", null, KeyEvent.VK_O, CTRL) { void run() { openFile(); } });
        file.add(new Act("Save", null, KeyEvent.VK_S, CTRL) { void run() { saveFile(); } });
        file.add(new Act("Save As\u2026", null, KeyEvent.VK_S, CTRL | KeyEvent.SHIFT_DOWN_MASK) { void run() { saveFileAs(); } });
        file.addSeparator();
        file.add(new Act("Exit", null, KeyEvent.VK_Q, CTRL) { void run() { exitApp(); } });

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic(KeyEvent.VK_E);
        edit.add(undoAct);
        edit.add(redoAct);
        edit.addSeparator();
        edit.add(deleteAct);
        edit.add(clearAct);

        JMenu tools = new JMenu("Tools");
        tools.setMnemonic(KeyEvent.VK_T);
        ButtonGroup group = new ButtonGroup();
        for (int t = TOOL_VERTEX; t <= TOOL_REMOVE; t++) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(toolActs[t]);
            group.add(item);
            toolMenuItems[t] = item;
            tools.add(item);
        }

        JMenu graphMenu = new JMenu("Graph");
        graphMenu.setMnemonic(KeyEvent.VK_G);
        for (int m = 0; m < 3; m++) {
            final int which = m;
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(MODE_LABELS[m]);
            item.setToolTipText(MODE_TIPS[m]);
            item.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    setMode(which, ((JCheckBoxMenuItem) e.getSource()).isSelected());
                }
            });
            modeItems[m] = item;
            graphMenu.add(item);
        }
        graphMenu.addSeparator();
        graphMenu.add(complementAct);
        graphMenu.add(compareAct);

        JMenu layout = new JMenu("Layout");
        layout.setMnemonic(KeyEvent.VK_L);
        layout.add(arrangeAct);

        JMenu view = new JMenu("View");
        view.setMnemonic(KeyEvent.VK_V);
        final String[] tabNames = {"Overview", "Vertices", "Matrices", "Containers", "Pair", "Degrees"};
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            view.add(new Act(tabNames[i] + " Tab", null, KeyEvent.VK_1 + i, CTRL) {
                void run() { rightTabs.setSelectedIndex(idx); }
            });
        }
        view.addSeparator();
        view.add(new Act("Recalculate", "Re-run the analysis", KeyEvent.VK_F5, 0) {
            void run() { recomputeAnalysis(); }
        });

        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        help.add(new Act("Quick Guide", null, KeyEvent.VK_F1, 0) {
            void run() { showHelp(); }
        });

        bar.add(new Wordmark());
        bar.add(file);
        bar.add(edit);
        bar.add(tools);
        bar.add(graphMenu);
        bar.add(layout);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    private void installKeyBindings() {
        bindKey(KeyEvent.VK_V, "toolVertex", toolActs[TOOL_VERTEX]);
        bindKey(KeyEvent.VK_E, "toolEdge", toolActs[TOOL_EDGE]);
        bindKey(KeyEvent.VK_M, "toolMove", toolActs[TOOL_MOVE]);
        bindKey(KeyEvent.VK_R, "toolRemove", toolActs[TOOL_REMOVE]);
        Action editSel = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (selE != null) {
                    setEdgeWeight(selE);
                } else if (selV != null) {
                    renameVertex(selV);
                }
            }
        };
        bindKey(KeyEvent.VK_ENTER, "editSelected", editSel);
        bindKey(KeyEvent.VK_F2, "editSelectedF2", editSel);
        bindKey(KeyEvent.VK_ESCAPE, "cancel", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                edgeFrom = null;
                dragV = null;
                setSelection(null, null);
            }
        });
    }

    private void bindKey(int key, String name, Action a) {
        JRootPane rp = frame.getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        rp.getActionMap().put(name, a);
    }

    private void showHelp() {
        String html = "<html><body style='width:400px'>"
                + "<b>Tools</b><br>"
                + "V \u2013 Add Vertex: click empty space<br>"
                + "E \u2013 Add Edge: drag from one vertex to another (back onto the same vertex = self-loop)<br>"
                + "M \u2013 Move: drag a vertex; double-click a vertex to rename<br>"
                + "R \u2013 Remove: click a vertex or edge<br><br>"
                + "<b>Graph type</b> (checkboxes in the toolbar / Graph menu)<br>"
                + "Directed \u2013 edges become arcs (tail \u2192 head). Weighted \u2013 edges carry a positive weight. "
                + "Multi \u2013 allow parallel edges and self-loops.<br><br>"
                + "<b>Editing</b><br>"
                + "Double-click an edge (any tool except Remove), or select it and press Enter / F2, to change its weight; "
                + "Delete \u2013 delete the selection; Esc \u2013 cancel; Ctrl+Z / Ctrl+Y \u2013 undo / redo; Ctrl+1\u20136 \u2013 switch tabs; "
                + "right-click for more (rename, weight, reverse, pair ends).<br><br>"
                + "<b>Tabs</b><br>"
                + "Overview (graph properties), Vertices (node properties), Matrices, Containers (D<sub>k</sub>), "
                + "Pair (adjacent, reachable, walk, trail, path, geodesic, semipath between two vertices), Degrees.<br><br>"
                + "<b>Graph menu</b> \u2013 complement, and isomorphism / subgraph comparison with a saved file.<br>"
                + "<b>Files</b> \u2013 Ctrl+O open, Ctrl+S save, Ctrl+Shift+S save as."
                + "</body></html>";
        JOptionPane.showMessageDialog(frame, html, "Quick Guide", JOptionPane.INFORMATION_MESSAGE);
    }

    private void selectTool(int tool) {
        selectedTool = tool;
        toolBtns[tool].setSelected(true);
        toolMenuItems[tool].setSelected(true);
        edgeFrom = null;
        dragV = null;
        setMessage(null);
        updateCursor();
        updateStatusBar();
        canvas.repaint();
    }

    private void updateCursor() {
        int c;
        switch (selectedTool) {
            case TOOL_VERTEX:
                c = (hoverV != null) ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR;
                break;
            case TOOL_EDGE:
                c = (hoverV != null) ? Cursor.HAND_CURSOR : Cursor.CROSSHAIR_CURSOR;
                break;
            case TOOL_MOVE:
                c = (hoverV != null) ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR;
                break;
            default:
                c = (hoverV != null || hoverE != null) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR;
        }
        canvas.setCursor(Cursor.getPredefinedCursor(c));
    }

    private void setSelection(Vertex v, Edge e) {
        selV = v;
        selE = e;
        updateActions();
        updateStatusBar();
        syncVertexRow();
        canvas.repaint();
    }

    /** Highlights the selected vertex's row in the Vertices tab. */
    private void syncVertexRow() {
        if (verticesTable == null || analyzedVersion != graphVersion) {
            return;
        }
        int idx = selV == null ? -1 : graph.vertices.indexOf(selV);
        boolean old = updatingTable;
        updatingTable = true;
        try {
            if (idx < 0 || idx >= verticesTable.getRowCount()) {
                verticesTable.clearSelection();
            } else {
                int vr = verticesTable.convertRowIndexToView(idx);
                verticesTable.setRowSelectionInterval(vr, vr);
                verticesTable.scrollRectToVisible(verticesTable.getCellRect(vr, 0, true));
            }
        } finally {
            updatingTable = old;
        }
    }

    private void selectVertexByIndex(int i) {
        if (analyzedVersion == graphVersion && i >= 0 && i < graph.vertices.size()) {
            setSelection(graph.vertices.get(i), null);
        }
    }

    private void setMessage(String m) {
        message = m;
        if (m != null) {
            msgTimer.restart();
        } else {
            msgTimer.stop();
        }
        updateStatusBar();
    }

    private void updateActions() {
        undoAct.setEnabled(!undoStack.isEmpty());
        redoAct.setEnabled(!redoStack.isEmpty());
        refreshKeyLabels();
        deleteAct.setEnabled(selV != null || selE != null);
        clearAct.setEnabled(!graph.vertices.isEmpty());
        arrangeAct.setEnabled(!graph.vertices.isEmpty());
        complementAct.setEnabled(!graph.vertices.isEmpty());
    }

    private void updateTitle() {
        String name = (currentFile == null) ? "Untitled" : currentFile.getName();
        frame.setTitle((dirty ? "*" : "") + name + " \u2014 " + baseTitle);
    }

    private String modeSummary() {
        return (graph.directed ? "Directed" : "Undirected") + (graph.weighted ? ", weighted" : "")
                + (graph.multi ? ", multi" : "");
    }

    private String toolName(int tool) {
        switch (tool) {
            case TOOL_VERTEX: return "Add Vertex";
            case TOOL_EDGE: return "Add Edge";
            case TOOL_MOVE: return "Move";
            case TOOL_REMOVE: return "Remove";
            default: return "";
        }
    }

    private void updateStatusBar() {
        if (statusLabel == null) {
            return;
        }
        statusLabel.setText(String.format(" Vertices: %d    Edges: %d    %s    Tool: %s%s",
                graph.vertices.size(), graph.edges.size(), modeSummary(), toolName(selectedTool),
                analyzing ? "    Analysing\u2026" : ""));
        updateHint();
    }

    private void syncModeControls() {
        for (int m = 0; m < 3; m++) {
            boolean val = (m == M_DIR) ? graph.directed : (m == M_WEIGHT) ? graph.weighted : graph.multi;
            modeBoxes[m].setSelected(val);
            modeItems[m].setSelected(val);
        }
    }

    private void setMode(int which, boolean state) {
        saveUndoState();
        if (which == M_DIR) graph.directed = state;
        else if (which == M_WEIGHT) graph.weighted = state;
        else if (which == M_MULTI) graph.multi = state;
        syncModeControls();
        graphChanged();
    }

    // ==================================================================
    // Change handling
    // ==================================================================
    private static final int TAB_OVERVIEW = 0, TAB_VERTICES = 1, TAB_MATRICES = 2, TAB_CONTAINERS = 3, TAB_PAIR = 4;

    private boolean containerBusy, updatingTable;

    private void graphChanged() {
        graphChanged(true);
    }

    /** Structure/mode changed: invalidate analysis flags and re-run the analysis. */
    private void graphChanged(boolean markDirty) {
        graphVersion++;
        if (markDirty) {
            dirty = true;
        }
        for (Vertex v : graph.vertices) {
            v.isCutVertex = false;
        }
        for (Edge e : graph.edges) {
            e.isBridge = false;
        }
        updateTitle();
        updateActions();
        updateStatusBar();
        refreshPanels();
        canvas.repaint();
    }

    /** Only positions changed: the analysis does not depend on them. */
    private void positionsChanged() {
        dirty = true;
        updateTitle();
        updateActions();
        canvas.repaint();
    }

    private void resetInteraction() {
        selV = hoverV = edgeFrom = dragV = null;
        selE = hoverE = null;
        preDrag = null;
    }

    // ==================================================================
    // Undo / redo (whole-graph snapshots)
    // ==================================================================
    private void pushUndo(Graph g) {
        undoStack.addLast(g);
        if (undoStack.size() > MAX_UNDO) {
            undoStack.removeFirst();
        }
        redoStack.clear();
    }

    private void saveUndoState() {
        pushUndo(graph.copy());
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.addLast(graph.copy());
        graph = undoStack.removeLast();
        resetInteraction();
        syncModeControls();
        graphChanged();
        setMessage("Undid last change");
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.addLast(graph.copy());
        graph = redoStack.removeLast();
        resetInteraction();
        syncModeControls();
        graphChanged();
        setMessage("Redid change");
    }

    // ==================================================================
    // Editing operations
    // ==================================================================
    private void deleteSelection() {
        if (selV != null) {
            Vertex v = selV;
            saveUndoState();
            graph.removeVertex(v);
            resetInteraction();
            graphChanged();
            setMessage("Vertex deleted \u2014 Ctrl+Z to undo");
        } else if (selE != null) {
            Edge e = selE;
            saveUndoState();
            graph.removeEdge(e);
            resetInteraction();
            graphChanged();
            setMessage("Edge deleted \u2014 Ctrl+Z to undo");
        }
    }

    private void clearAll() {
        if (graph.vertices.isEmpty()) {
            return;
        }
        saveUndoState();
        Graph g = new Graph();
        g.directed = graph.directed;
        g.weighted = graph.weighted;
        g.multi = graph.multi;
        graph = g;
        resetInteraction();
        graphChanged();
        setMessage("Cleared \u2014 Ctrl+Z to undo");
    }

    private void arrangeVertices() {
        int n = graph.vertices.size();
        if (n == 0) {
            return;
        }
        saveUndoState();
        double cx = canvas.getWidth() / 2.0, cy = canvas.getHeight() / 2.0;
        double radius = Math.max(0, Math.min(canvas.getWidth(), canvas.getHeight()) / 2.0 - 2 * VERTEX_RADIUS - 24);
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / n;
            Vertex v = graph.vertices.get(i);
            v.location.x = (int) Math.round(cx + (n == 1 ? 0 : Math.cos(a) * radius));
            v.location.y = (int) Math.round(cy + (n == 1 ? 0 : Math.sin(a) * radius));
        }
        positionsChanged();
    }

    private void replaceWithComplement() {
        if (graph.vertices.isEmpty()) {
            return;
        }
        saveUndoState();
        graph = graph.complement();
        resetInteraction();
        syncModeControls();
        graphChanged();
        setMessage("Replaced with the complement (weights reset to 1) \u2014 Ctrl+Z to undo");
    }

    private void renameVertex(Vertex v) {
        String input = v.name;
        while (true) {
            Object r = JOptionPane.showInputDialog(frame, "Vertex name (1\u20134 characters, no spaces, unique):",
                    "Rename Vertex", JOptionPane.QUESTION_MESSAGE, null, null, input);
            if (r == null) {
                return;
            }
            input = r.toString().trim();
            String err = null;
            if (input.length() < 1 || input.length() > 4 || input.matches(".*\\s.*")) {
                err = "Use 1 to 4 characters without spaces.";
            } else {
                for (Vertex o : graph.vertices) {
                    if (o != v && o.name.equals(input)) {
                        err = "Another vertex already uses that name.";
                    }
                }
            }
            if (err == null) {
                break;
            }
            JOptionPane.showMessageDialog(frame, err, "Invalid name", JOptionPane.WARNING_MESSAGE);
        }
        if (input.equals(v.name)) {
            return;
        }
        saveUndoState();
        v.name = input;
        graphChanged();
    }

    /** Asks for a positive weight; returns null if cancelled. */
    private Double askWeight(double initial) {
        String input = Ui.fmt(initial);
        while (true) {
            Object r = JOptionPane.showInputDialog(frame, "Edge weight (a positive number):", "Edge Weight",
                    JOptionPane.QUESTION_MESSAGE, null, null, input);
            if (r == null) {
                return null;
            }
            input = r.toString();
            double w = FileManager.parseWeight(input);
            if (!Double.isNaN(w)) {
                return w;
            }
            JOptionPane.showMessageDialog(frame, "Please enter a number greater than 0.", "Invalid weight",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void setEdgeWeight(Edge e) {
        if (!graph.weighted) {
            setMessage("Turn on \"Weighted\" to edit edge weights");
            return;
        }
        Double w = askWeight(e.weight);
        if (w == null || w == e.weight) {
            return;
        }
        saveUndoState();
        e.weight = w;
        graphChanged();
    }

    private String edgeBlockedReason(Vertex a, Vertex b) {
        if (a == b) {
            return graph.multi ? null : "Self-loops need the \"Multi\" option (parallel edges & loops)";
        }
        if (!graph.multi && graph.countBetween(a, b) > 0) {
            return graph.directed ? "That arc already exists \u2014 enable \"Multi\" for parallel edges"
                    : "Already connected \u2014 enable \"Multi\" for parallel edges";
        }
        return null;
    }

    private void createEdge(Vertex from, Vertex to) {
        String why = edgeBlockedReason(from, to);
        if (why != null) {
            setMessage(why);
            return;
        }
        double w = 1.0;
        if (graph.weighted) {
            Double asked = askWeight(1.0);
            if (asked == null) {
                setMessage("Edge cancelled");
                return;
            }
            w = asked;
        }
        saveUndoState();
        Edge e = graph.addEdge(from, to, w);
        setSelection(null, e);
        graphChanged();
    }

    private void tryAddVertex(int x, int y) {
        Vertex hit = vertexAt(x, y);
        if (hit != null) {
            setSelection(hit, null);
            setMessage("A vertex is already there");
            return;
        }
        for (Vertex o : graph.vertices) {
            if (o.location.distance(x, y) < 2 * VERTEX_RADIUS + 6) {
                setMessage("Too close to another vertex");
                return;
            }
        }
        saveUndoState();
        Vertex v = new Vertex(graph.nextName(),
                clamp(x, VERTEX_RADIUS, Math.max(VERTEX_RADIUS, canvas.getWidth() - VERTEX_RADIUS)),
                clamp(y, VERTEX_RADIUS, Math.max(VERTEX_RADIUS, canvas.getHeight() - VERTEX_RADIUS)));
        graph.vertices.add(v);
        setSelection(v, null);
        graphChanged();
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ==================================================================
    // Files
    // ==================================================================
    private boolean confirmDiscard() {
        if (!dirty) {
            return true;
        }
        String name = (currentFile == null) ? "Untitled" : currentFile.getName();
        Object[] opts = {"Save", "Don't Save", "Cancel"};
        int r = JOptionPane.showOptionDialog(frame, "Save changes to \"" + name + "\" before continuing?",
                "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
        if (r == 0) {
            return saveFile();
        }
        return r == 1;
    }

    private void exitApp() {
        if (confirmDiscard()) {
            frame.dispose();
            System.exit(0);
        }
    }

    private void newGraph() {
        if (!confirmDiscard()) {
            return;
        }
        graph = new Graph();
        resetInteraction();
        undoStack.clear();
        redoStack.clear();
        currentFile = null;
        syncModeControls();
        graphChanged(false);
        dirty = false;
        updateTitle();
    }

    private void openFile() {
        if (!confirmDiscard()) {
            return;
        }
        JFileChooser jF = fileManager.jF;
        jF.setDialogTitle("Open Graph");
        if (jF.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = jF.getSelectedFile();
        Graph g = fileManager.loadFile(f);
        if (g == null) {
            JOptionPane.showMessageDialog(frame,
                    "Couldn't open \"" + f.getName() + "\".\nIt isn't a valid graph file, or it can't be read.",
                    "Open failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        graph = g;
        boolean allAtOrigin = !graph.vertices.isEmpty();
        for (Vertex v : graph.vertices) {
            if (v.location.x != 0 || v.location.y != 0) {
                allAtOrigin = false;
            }
        }
        resetInteraction();
        undoStack.clear();
        redoStack.clear();
        currentFile = f;
        if (allAtOrigin) {
            arrangeVertices();
            undoStack.clear();
        }
        syncModeControls();
        graphChanged(false);
        dirty = false;
        updateTitle();
        setMessage("Opened " + f.getName());
    }

    private boolean saveFile() {
        return (currentFile == null) ? saveFileAs() : writeTo(currentFile);
    }

    private boolean saveFileAs() {
        JFileChooser jF = fileManager.jF;
        jF.setDialogTitle("Save Graph");
        jF.setSelectedFile(currentFile != null ? currentFile : new File("graph.txt"));
        if (jF.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        File f = jF.getSelectedFile();
        if (!f.getName().contains(".")) {
            f = new File(f.getParentFile(), f.getName() + ".txt");
        }
        if (f.exists() && !f.equals(currentFile)) {
            int r = JOptionPane.showConfirmDialog(frame, "\"" + f.getName() + "\" already exists. Replace it?",
                    "Confirm Save", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return writeTo(f);
    }

    private boolean writeTo(File f) {
        if (!fileManager.saveFile(graph, f)) {
            JOptionPane.showMessageDialog(frame, "Couldn't save to \"" + f.getName() + "\".", "Save failed",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        currentFile = f;
        dirty = false;
        updateTitle();
        setMessage("Saved " + f.getName());
        return true;
    }

    private void compareWithFile() {
        if (graph.vertices.isEmpty()) {
            setMessage("Draw a graph first, then compare it with a file");
            return;
        }
        JFileChooser jF = fileManager.jF;
        jF.setDialogTitle("Compare With Graph File");
        if (jF.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = jF.getSelectedFile();
        Graph other = fileManager.loadFile(f);
        if (other == null) {
            JOptionPane.showMessageDialog(frame, "Couldn't read \"" + f.getName() + "\" as a graph file.",
                    "Compare failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        GraphData a = GraphData.of(graph), b = GraphData.of(other);
        StringBuilder sb = new StringBuilder();
        sb.append("Current graph : ").append(a.n).append(" vertices, ").append(a.m).append(" edges")
                .append(a.directed ? " (directed)" : "").append("\n");
        sb.append("File graph    : ").append(f.getName()).append(" \u2014 ").append(b.n).append(" vertices, ")
                .append(b.m).append(" edges").append(b.directed ? " (directed)" : "").append("\n\n");
        if (a.directed != b.directed) {
            sb.append("One graph is directed and the other is not, so no structural comparison applies.\n");
        } else {
            sb.append("Same labelled graph (names + edges):  ").append(GraphCompare.sameLabelledGraph(a, b) ? "yes" : "no").append("\n");
            sb.append("Current \u2286 file (matching vertex names):  ").append(GraphCompare.labelSubgraph(a, b, false) ? "yes" : "no").append("\n");
            sb.append("File \u2286 current (matching vertex names):  ").append(GraphCompare.labelSubgraph(b, a, false) ? "yes" : "no").append("\n\n");
            sb.append("Isomorphic (up to renaming):  ").append(describeMatch(GraphCompare.isomorphism(a, b), a, b)).append("\n");
            sb.append("Current is a subgraph of file graph (up to renaming):  ").append(describeMatch(GraphCompare.embedding(a, b), a, b)).append("\n");
            sb.append("File graph is a subgraph of current (up to renaming):  ").append(describeMatch(GraphCompare.embedding(b, a), b, a)).append("\n");
            sb.append("\nWeights are ignored; parallel edges and loops are compared.\n");
        }
        JTextArea ta = new JTextArea(sb.toString());
        ta.setEditable(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(620, 240));
        JOptionPane.showMessageDialog(frame, sp, "Compare With File", JOptionPane.INFORMATION_MESSAGE);
    }

    private String describeMatch(GraphCompare.Match m, GraphData a, GraphData b) {
        if (m.note != null) {
            return "n/a (" + m.note + ")";
        }
        if (m.map != null) {
            return "yes  [" + GraphCompare.mappingString(a, b, m.map) + "]";
        }
        return m.exhausted ? "undetermined (search limit reached)" : "no";
    }

    // ==================================================================
    // Analysis (background thread, immutable snapshot)
    // ==================================================================
    private void recomputeAnalysis() {
        graphVersion++;
        refreshPanels();
    }

    private void refreshPanels() {
        startAnalysis();
    }

    private void startAnalysis() {
        if (analyzing) {
            return;         // done() chases the latest version
        }
        if (graph.vertices.isEmpty()) {
            gA = new GraphAnalyzer();
            curData = GraphData.of(graph);
            gA.analyze(curData);
            adjM = null;
            weightM = null;
            distM = null;
            containerResult = null;
            analyzedVersion = graphVersion;
            updateAllTabs();
            return;
        }
        final int version = graphVersion;
        final GraphData data = GraphData.of(graph);
        final boolean auto = data.n <= GraphProperties.AUTO_CONTAINER_LIMIT;
        analyzing = true;
        busyBar.setVisible(true);
        updateStatusBar();
        new SwingWorker<Object[], Void>() {
            protected Object[] doInBackground() {
                GraphAnalyzer an = new GraphAnalyzer();
                an.analyze(data);
                return new Object[]{an, GraphProperties.adjacencyMatrix(data), GraphProperties.weightMatrix(data),
                    GraphProperties.distanceMatrix(data), auto ? GraphProperties.computeContainers(data) : null};
            }

            protected void done() {
                analyzing = false;
                busyBar.setVisible(false);
                try {
                    Object[] r = get();
                    gA = (GraphAnalyzer) r[0];
                    adjM = (int[][]) r[1];
                    weightM = (double[][]) r[2];
                    distM = (double[][]) r[3];
                    containerResult = (GraphProperties.ContainerResult) r[4];
                    curData = data;
                    analyzedVersion = version;
                    if (version == graphVersion) {
                        applyAnalysisFlags();
                    }
                    updateAllTabs();
                } catch (Exception ex) {
                    analyzedVersion = version;
                    ex.printStackTrace();
                    setMessage("Analysis failed \u2014 see console for details");
                }
                updateStatusBar();
                if (analyzedVersion != graphVersion) {
                    startAnalysis();
                }
            }
        }.execute();
    }

    private void applyAnalysisFlags() {
        for (Vertex v : graph.vertices) {
            v.isCutVertex = false;
        }
        for (Edge e : graph.edges) {
            e.isBridge = false;
        }
        for (int i : gA.cutVertices) {
            if (i >= 0 && i < graph.vertices.size()) {
                graph.vertices.get(i).isCutVertex = true;
            }
        }
        for (int i : gA.bridges) {
            if (i >= 0 && i < graph.edges.size()) {
                graph.edges.get(i).isBridge = true;
            }
        }
    }

    private void updateAllTabs() {
        updateOverviewTab();
        updateVerticesTab();
        updateMatricesTab();
        updateContainerTab();
        refreshPairCombos();
        updateDegreeDistributionTab();
        applyHighlights();
        canvas.repaint();
    }

    /** Colours vertices/edges according to the tab being viewed (Pair tab: from/to + geodesic). */
    private void applyHighlights() {
        for (Vertex v : graph.vertices) {
            v.role = 0;
        }
        for (Edge e : graph.edges) {
            e.onPath = false;
        }
        if (rightTabs != null && rightTabs.getSelectedIndex() == TAB_PAIR && pairResult != null
                && analyzedVersion == graphVersion && pairResult.d.n == graph.vertices.size()) {
            graph.vertices.get(pairResult.s).role = 1;
            graph.vertices.get(pairResult.t).role = 2;
            for (int i : pairResult.geodesicEdges()) {
                if (i >= 0 && i < graph.edges.size()) {
                    graph.edges.get(i).onPath = true;
                }
            }
        }
    }

    // ==================================================================
    // Tabs
    // ==================================================================
    private JPanel buildOverviewTab() {
        overviewPanel = new JPanel(new GridBagLayout());
        overviewPanel.setBackground(Color.WHITE);
        overviewPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        return overviewPanel;
    }

    private void updateOverviewTab() {
        overviewPanel.removeAll();
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.NORTHWEST;
        int row = 0;
        if (gA.getN() == 0) {
            JLabel l = new JLabel("Add vertices to see the graph's properties.");
            l.setForeground(Ui.MUTED);
            gc.gridy = row++;
            overviewPanel.add(l, gc);
        } else {
            for (GraphAnalyzer.Row r : gA.overview) {
                gc.gridy = row++;
                overviewPanel.add(r.kind == GraphAnalyzer.KIND_SECTION ? sectionHeader(r) : propRow(r), gc);
            }
        }
        gc.gridy = row;
        gc.weighty = 1;
        gc.fill = GridBagConstraints.BOTH;
        overviewPanel.add(Box.createGlue(), gc);
        overviewPanel.revalidate();
        overviewPanel.repaint();
    }

    private JComponent sectionHeader(GraphAnalyzer.Row r) {
        JLabel l = new JLabel(r.name);
        l.setOpaque(true);
        l.setBackground(Ui.tint(r.color, 0.82f));
        l.setForeground(new Color(r.color).darker());
        l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
        l.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        l.setToolTipText(r.tip);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        p.add(l, BorderLayout.CENTER);
        return p;
    }

    private JComponent propRow(GraphAnalyzer.Row r) {
        Color c;
        switch (r.state) {
            case GraphAnalyzer.YES: c = Ui.GOOD; break;
            case GraphAnalyzer.NO: c = Ui.MUTED; break;
            case GraphAnalyzer.WARN: c = Ui.WARN; break;
            default: c = Ui.INK;
        }
        JLabel name = new JLabel(r.name);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
        name.setVerticalAlignment(SwingConstants.TOP);
        name.setPreferredSize(new Dimension(150, 18));
        name.setToolTipText(r.tip);
        JLabel val = new JLabel("<html><body style='width:240px;color:" + Ui.hex(c) + "'>" + Ui.esc(r.value) + "</body></html>");
        val.setVerticalAlignment(SwingConstants.TOP);
        val.setToolTipText(r.tip);
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        p.add(name, BorderLayout.WEST);
        p.add(val, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildVerticesTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        verticesTable = new JTable(new DefaultTableModel()) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return getParent() instanceof JViewport && getParent().getWidth() >= getPreferredSize().width;
            }
        };
        verticesTable.setAutoCreateRowSorter(true);
        styleVerticesTable();
        verticesTable.getSelectionModel().addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (e.getValueIsAdjusting() || updatingTable) {
                    return;
                }
                int viewRow = verticesTable.getSelectedRow();
                if (viewRow < 0) {
                    return;
                }
                int idx = verticesTable.convertRowIndexToModel(viewRow);
                if (idx >= 0 && idx < graph.vertices.size() && analyzedVersion == graphVersion) {
                    setSelection(graph.vertices.get(idx), null);
                }
            }
        });
        JScrollPane vs = new JScrollPane(verticesTable);
        vs.setBorder(null);
        vs.getViewport().setBackground(Color.WHITE);
        p.add(vs, BorderLayout.CENTER);
        JLabel hint = new JLabel("Click a row to select that vertex on the canvas; click a header to sort.");
        hint.setFont(Ui.font(Font.PLAIN, 12));
        hint.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        hint.setForeground(Ui.MUTED);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private void styleVerticesTable() {
        JTable t = verticesTable;
        t.setFont(Ui.font(Font.PLAIN, 12));
        t.setRowHeight(28);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setGridColor(Ui.GRID);
        t.setFillsViewportHeight(true);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        t.setSelectionBackground(Ui.tint(0x2D6CDF, 0.85f));
        t.setSelectionForeground(Ui.INK);
        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setFont(Ui.font(Font.BOLD, 12));
        h.setPreferredSize(new Dimension(100, 30));
        h.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(tb, v, sel, false, row, col);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                setHorizontalAlignment(col == 0 ? SwingConstants.LEFT : SwingConstants.CENTER);
                String s = String.valueOf(v);
                boolean yes = "yes".equals(s);
                setFont(Ui.font(col == 0 || yes ? Font.BOLD : Font.PLAIN, 12));
                if (!sel) {
                    setBackground(Color.WHITE);
                }
                if ("no".equals(s)) {
                    setText("\u2013");
                    setForeground(Ui.FAINT);
                } else if (yes) {
                    setForeground("Cutpoint".equals(tb.getColumnName(col)) ? Ui.WARN : Ui.ACCENT);
                } else {
                    setForeground(Ui.INK);
                }
                return this;
            }
        };
        t.setDefaultRenderer(Object.class, r);
        t.setDefaultRenderer(Number.class, r);
        t.setDefaultRenderer(Double.class, r);
    }

    private void updateVerticesTab() {
        final Class<?>[] cls = gA.nodeClasses;
        DefaultTableModel m = new DefaultTableModel(gA.nodeRows, gA.nodeColumns) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {
                return (c < cls.length && cls[c] != null) ? cls[c] : Object.class;
            }
        };
        updatingTable = true;
        verticesTable.setModel(m);
        for (int c = 0; c < verticesTable.getColumnCount(); c++) {
            javax.swing.table.TableColumn col = verticesTable.getColumnModel().getColumn(c);
            FontMetrics hf = verticesTable.getTableHeader().getFontMetrics(verticesTable.getTableHeader().getFont());
            FontMetrics bf = verticesTable.getFontMetrics(Ui.font(Font.BOLD, 12));
            int w = hf.stringWidth(String.valueOf(col.getHeaderValue())) + 34;   // room for the sort arrow
            for (int r = 0; r < verticesTable.getRowCount(); r++) {
                w = Math.max(w, bf.stringWidth(String.valueOf(verticesTable.getValueAt(r, c))) + 22);
            }
            col.setMinWidth(w);
            col.setPreferredWidth(w);
        }
        updatingTable = false;
        syncVertexRow();
    }

    private JPanel buildMatricesTab() {
        matricesPanel = new TrackingPanel();
        matricesPanel.setLayout(new BoxLayout(matricesPanel, BoxLayout.Y_AXIS));
        matricesPanel.setBackground(Color.WHITE);
        matricesPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return matricesPanel;
    }

    private void updateMatricesTab() {
        matricesPanel.removeAll();
        if (curData == null || curData.n == 0 || adjM == null) {
            JLabel l = new JLabel("Add vertices to see matrices here.");
            l.setForeground(Ui.MUTED);
            matricesPanel.add(l);
        } else {
            int n = curData.n;
            String[][] adj = new String[n][n];
            String[][] dist = new String[n][n];
            String[][] wt = new String[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    adj[i][j] = String.valueOf(adjM[i][j]);
                    dist[i][j] = Ui.fmt(distM[i][j]);
                    wt[i][j] = (weightM[i][j] == GraphData.INF) ? "\u2013" : Ui.fmt(weightM[i][j]);
                }
            }
            addMatrix("Adjacency matrix  (number of edges; diagonal = loops)", curData.names, adj);
            if (curData.weighted) {
                addMatrix("Weight matrix  (lightest edge between each pair)", curData.names, wt);
            }
            addMatrix(curData.weighted ? "Shortest distance  (total weight, \u221E = unreachable)"
                    : "Shortest distance  (number of edges, \u221E = unreachable)", curData.names, dist);
        }
        matricesPanel.add(Box.createVerticalGlue());
        matricesPanel.revalidate();
        matricesPanel.repaint();
    }

    private void addMatrix(String title, final String[] names, String[][] cells) {
        final int n = names.length;
        final boolean dir = curData != null && curData.directed;
        final int rowH = 32, headH = 34;
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.setBorder(BorderFactory.createEmptyBorder(8, 0, 6, 0));
        String[] cols = new String[n + 1];
        cols[0] = "";
        Object[][] data = new Object[n][n + 1];
        int maxLen = 1, maxName = 1;
        for (int j = 0; j < n; j++) {
            cols[j + 1] = names[j];
            maxName = Math.max(maxName, names[j].length());
        }
        for (int i = 0; i < n; i++) {
            data[i][0] = names[i];
            for (int j = 0; j < n; j++) {
                data[i][j + 1] = cells[i][j];
                maxLen = Math.max(maxLen, cells[i][j].length());
            }
        }
        DefaultTableModel m = new DefaultTableModel(data, cols) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        final JTable tb = new JTable(m) {
            @Override
            public String getToolTipText(MouseEvent ev) {
                int r = rowAtPoint(ev.getPoint()), c = columnAtPoint(ev.getPoint());
                if (r < 0 || c < 1) {
                    return null;
                }
                return names[r] + (dir ? " \u2192 " : " \u2013 ") + names[c - 1];
            }
        };
        // columns stretch to fill the tab; the table only scrolls sideways when it truly cannot fit
        tb.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        tb.setRowHeight(rowH);
        tb.setFont(Ui.font(Font.PLAIN, 13));
        tb.setShowGrid(true);
        tb.setGridColor(Ui.GRID);
        tb.setCellSelectionEnabled(true);
        tb.setSelectionBackground(Ui.tint(0x2D6CDF, 0.78f));
        tb.setSelectionForeground(Ui.INK);
        final JTableHeader hdr = tb.getTableHeader();
        hdr.setReorderingAllowed(false);
        hdr.setResizingAllowed(false);
        hdr.setFont(Ui.font(Font.BOLD, 13));
        hdr.setPreferredSize(new Dimension(100, headH));
        hdr.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hdr.setToolTipText("Click to select this vertex on the canvas");
        hdr.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int c = hdr.columnAtPoint(e.getPoint());
                if (c >= 1) {
                    selectVertexByIndex(c - 1);
                }
            }
        });
        tb.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = tb.rowAtPoint(e.getPoint()), c = tb.columnAtPoint(e.getPoint());
                if (r >= 0 && c == 0) {
                    selectVertexByIndex(r);
                }
            }
        });
        DefaultTableCellRenderer centre = new DefaultTableCellRenderer();
        centre.setHorizontalAlignment(SwingConstants.CENTER);
        DefaultTableCellRenderer head = new DefaultTableCellRenderer();
        head.setHorizontalAlignment(SwingConstants.CENTER);
        head.setFont(Ui.font(Font.BOLD, 13));
        head.setBackground(Ui.SURFACE);
        head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int cw = Math.max(46, maxLen * 9 + 18);
        int hw = Math.max(52, maxName * 9 + 22);
        for (int c = 0; c < tb.getColumnCount(); c++) {
            tb.getColumnModel().getColumn(c).setPreferredWidth(c == 0 ? hw : cw);
            tb.getColumnModel().getColumn(c).setMinWidth(c == 0 ? hw : 40);
            tb.getColumnModel().getColumn(c).setCellRenderer(c == 0 ? head : centre);
        }
        JPanel body = new JPanel(new BorderLayout());
        body.add(hdr, BorderLayout.NORTH);
        body.add(tb, BorderLayout.CENTER);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        int h = headH + n * (rowH + 1) + 2;
        body.setPreferredSize(new Dimension(hw + n * cw, h));
        body.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        matricesPanel.add(t);
        matricesPanel.add(body);
        matricesPanel.add(Box.createVerticalStrut(12));
    }

    private JPanel buildContainersTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        containerBtn = new JButton("Compute containers");
        containerBtn.setToolTipText("Vertex-disjoint path containers and the k-wide diameters D_k(G)");
        containerBtn.setFocusable(false);
        containerBtn.setMargin(new Insets(5, 14, 5, 14));
        containerBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                computeContainersNow();
            }
        });
        containerStatus = new JLabel(" ");
        containerStatus.setForeground(Ui.MUTED);
        containerDiameters = new JLabel(" ");
        containerDiameters.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        line.setOpaque(false);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        line.add(containerBtn);
        line.add(containerStatus);
        containerDiameters.setAlignmentX(Component.LEFT_ALIGNMENT);
        containerDiameters.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 0));
        top.add(line);
        top.add(containerDiameters);
        containerTable = new JTable(new DefaultTableModel());
        containerTable.setRowHeight(22);
        containerDetail = new JTextArea();
        containerDetail.setEditable(false);
        containerDetail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(containerTable), new JScrollPane(containerDetail));
        sp.setResizeWeight(0.4);
        sp.setBorder(null);
        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private void updateContainerTab() {
        int n = curData == null ? 0 : curData.n;
        boolean can = n >= 2 && n <= GraphProperties.CONTAINER_LIMIT;
        containerBtn.setEnabled(can && !containerBusy);
        DefaultTableModel m = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        if (n < 2) {
            containerStatus.setText("Add at least two vertices.");
        } else if (n > GraphProperties.CONTAINER_LIMIT) {
            containerStatus.setText("Containers grow exponentially: limited to " + GraphProperties.CONTAINER_LIMIT
                    + " vertices (this graph has " + n + ").");
        } else if (containerBusy) {
            containerStatus.setText("Computing\u2026");
        } else if (containerResult == null) {
            containerStatus.setText("Not computed yet \u2014 press the button.");
        } else {
            containerStatus.setText("Up to date" + (containerResult.truncated ? " (path cap reached for some pairs)" : ""));
        }
        if (can && containerResult != null) {
            m.setDataVector(containerResult.rows, containerResult.columns);
            containerDiameters.setText(containerResult.diameterLine.isEmpty() ? " " : containerResult.diameterLine);
            containerDetail.setText(containerResult.detail);
            containerDetail.setCaretPosition(0);
        } else {
            containerDiameters.setText(" ");
            containerDetail.setText("");
        }
        containerTable.setModel(m);
    }

    private void computeContainersNow() {
        if (curData == null || containerBusy || curData.n < 2 || curData.n > GraphProperties.CONTAINER_LIMIT) {
            return;
        }
        final GraphData data = curData;
        containerBusy = true;
        updateContainerTab();
        new SwingWorker<GraphProperties.ContainerResult, Void>() {
            protected GraphProperties.ContainerResult doInBackground() {
                return GraphProperties.computeContainers(data);
            }

            protected void done() {
                containerBusy = false;
                try {
                    GraphProperties.ContainerResult r = get();
                    if (curData == data) {
                        containerResult = r;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    setMessage("Container analysis failed \u2014 see console");
                }
                updateContainerTab();
            }
        }.execute();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private JPanel buildPairTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        pairFrom = new JComboBox();
        pairTo = new JComboBox();
        pairFrom.setFocusable(false);
        pairTo.setFocusable(false);
        ActionListener l = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!updatingCombos) {
                    updatePairView();
                    applyHighlights();
                    canvas.repaint();
                }
            }
        };
        pairFrom.addActionListener(l);
        pairTo.addActionListener(l);
        JButton swap = new JButton("\u2194  Swap");
        swap.setToolTipText("Swap From and To");
        swap.setFocusable(false);
        swap.setMargin(new Insets(4, 12, 4, 12));
        swap.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int a = pairFrom.getSelectedIndex(), b = pairTo.getSelectedIndex();
                if (a < 0 || b < 0) {
                    return;
                }
                updatingCombos = true;
                pairFrom.setSelectedIndex(b);
                pairTo.setSelectedIndex(a);
                updatingCombos = false;
                updatePairView();
                applyHighlights();
                canvas.repaint();
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        top.setOpaque(false);
        top.add(new JLabel("From"));
        top.add(pairFrom);
        top.add(new JLabel("To"));
        top.add(pairTo);
        top.add(swap);
        pairView = new JEditorPane("text/html", "");
        pairView.setEditable(false);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(pairView), BorderLayout.CENTER);
        return p;
    }

    @SuppressWarnings("unchecked")
    private void refreshPairCombos() {
        updatingCombos = true;
        int a = pairFrom.getSelectedIndex(), b = pairTo.getSelectedIndex();
        pairFrom.removeAllItems();
        pairTo.removeAllItems();
        int n = curData == null ? 0 : curData.n;
        for (int i = 0; i < n; i++) {
            pairFrom.addItem(curData.names[i]);
            pairTo.addItem(curData.names[i]);
        }
        if (n > 0) {
            pairFrom.setSelectedIndex(a >= 0 && a < n ? a : 0);
            pairTo.setSelectedIndex(b >= 0 && b < n ? b : Math.min(1, n - 1));
        }
        updatingCombos = false;
        updatePairView();
    }

    private void updatePairView() {
        pairResult = null;
        if (curData == null || curData.n < 2) {
            pairView.setText("<html><body style='font-family:Segoe UI,sans-serif;font-size:11px;color:#6B7280'>"
                    + "Add at least two vertices to explore adjacency, paths, trails, walks, geodesics and semipaths "
                    + "between a pair.</body></html>");
            return;
        }
        int s = pairFrom.getSelectedIndex(), t = pairTo.getSelectedIndex();
        if (s < 0 || t < 0 || s >= curData.n || t >= curData.n) {
            return;
        }
        VertexPair vp = new VertexPair(curData, s, t);
        vp.analyze();
        pairResult = vp;
        pairView.setText(vp.toHtml());
        pairView.setCaretPosition(0);
    }

    private void setPairEnd(Vertex v, boolean from) {
        int idx = graph.vertices.indexOf(v);
        if (idx < 0 || idx >= pairFrom.getItemCount() || analyzedVersion != graphVersion) {
            setMessage("Wait for the analysis to finish, then try again");
            return;
        }
        (from ? pairFrom : pairTo).setSelectedIndex(idx);
        rightTabs.setSelectedIndex(TAB_PAIR);
    }

    // ==================================================================
    // Canvas & mouse input
    // ==================================================================
    private Vertex vertexAt(int x, int y) {
        for (int i = graph.vertices.size() - 1; i >= 0; i--) {
            if (graph.vertices.get(i).hasIntersection(x, y)) {
                return graph.vertices.get(i);
            }
        }
        return null;
    }

    private Edge edgeAt(int x, int y) {
        graph.layoutEdges();
        for (int i = graph.edges.size() - 1; i >= 0; i--) {
            if (graph.edges.get(i).hasIntersection(x, y)) {
                return graph.edges.get(i);
            }
        }
        return null;
    }

    private String toolHint(int t) {
        switch (t) {
            case TOOL_VERTEX: return "Click empty space to place a vertex";
            case TOOL_EDGE: return graph.directed ? "Drag from the tail vertex to the head vertex"
                    : "Drag from one vertex to another (back onto the same vertex = self-loop, in Multi mode)";
            case TOOL_MOVE: return "Drag a vertex to move it; double-click a vertex to rename, an edge to edit its weight";
            case TOOL_REMOVE: return "Click a vertex or edge to delete it";
            default: return "";
        }
    }

    private void updateHint() {
        if (hintLabel == null) {
            return;
        }
        String hint;
        if (message != null) {
            hint = message;
        } else if (edgeFrom != null) {
            if (hoverV == null) {
                hint = "Release on another vertex to connect (Esc to cancel)";
            } else if (hoverV == edgeFrom) {
                hint = edgeLeft ? "Release to add a self-loop" : "";
            } else {
                String why = edgeBlockedReason(edgeFrom, hoverV);
                hint = why != null ? why : "Release to connect";
            }
        } else if (hoverV != null) {
            hint = "Vertex \"" + hoverV.name + "\" \u2014 " + (graph.directed
                    ? "in " + graph.inDegree(hoverV) + ", out " + graph.outDegree(hoverV)
                    : "degree " + graph.degree(hoverV));
        } else if (hoverE != null) {
            hint = "Edge " + hoverE.vertex1.name + (graph.directed ? "\u2192" : "\u2013") + hoverE.vertex2.name
                    + (graph.weighted ? "  weight " + Ui.fmt(hoverE.weight) + "  \u2014 double-click to change" : "");
        } else {
            hint = toolHint(selectedTool);
        }
        hintLabel.setText(hint + " ");
    }

    private void showContextMenu(MouseEvent e) {
        final int px = e.getX(), py = e.getY();
        final Vertex v = vertexAt(px, py);
        final Edge ed = (v == null) ? edgeAt(px, py) : null;
        setSelection(v, ed);
        JPopupMenu m = new JPopupMenu();
        if (v != null) {
            m.add(menuItem("Rename\u2026", true, new Runnable() {
                public void run() { renameVertex(v); }
            }));
            m.add(menuItem("Add Self-Loop", graph.multi, new Runnable() {
                public void run() { createEdge(v, v); }
            }));
            m.addSeparator();
            m.add(menuItem("Use as Pair \u201CFrom\u201D", true, new Runnable() {
                public void run() { setPairEnd(v, true); }
            }));
            m.add(menuItem("Use as Pair \u201CTo\u201D", true, new Runnable() {
                public void run() { setPairEnd(v, false); }
            }));
            m.addSeparator();
            m.add(menuItem("Delete Vertex", true, new Runnable() {
                public void run() { deleteSelection(); }
            }));
        } else if (ed != null) {
            m.add(menuItem("Set Weight\u2026", graph.weighted, new Runnable() {
                public void run() { setEdgeWeight(ed); }
            }));
            m.add(menuItem("Reverse Direction", graph.directed && !ed.isLoop(), new Runnable() {
                public void run() {
                    saveUndoState();
                    Vertex t = ed.vertex1;
                    ed.vertex1 = ed.vertex2;
                    ed.vertex2 = t;
                    graphChanged();
                }
            }));
            m.addSeparator();
            m.add(menuItem("Delete Edge", true, new Runnable() {
                public void run() { deleteSelection(); }
            }));
        } else {
            m.add(menuItem("Add Vertex Here", true, new Runnable() {
                public void run() { tryAddVertex(px, py); }
            }));
        }
        m.show(canvas, px, py);
    }

    private JMenuItem menuItem(String text, boolean enabled, final Runnable r) {
        JMenuItem it = new JMenuItem(text);
        it.setEnabled(enabled);
        it.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
        return it;
    }

    private class InputListener extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent e) {
            canvas.requestFocusInWindow();
            if (e.isPopupTrigger()) {
                showContextMenu(e);
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            int x = e.getX(), y = e.getY();
            Vertex v = vertexAt(x, y);
            cursorPt = e.getPoint();
            switch (selectedTool) {
                case TOOL_VERTEX:
                    if (v == null && edgeAt(x, y) != null) {
                        setSelection(null, edgeAt(x, y));
                    } else {
                        tryAddVertex(x, y);
                    }
                    break;
                case TOOL_EDGE:
                    edgeLeft = false;
                    if (v != null) {
                        edgeFrom = v;
                        setSelection(v, null);
                    } else {
                        setSelection(null, edgeAt(x, y));
                    }
                    break;
                case TOOL_MOVE:
                    if (v != null) {
                        setSelection(v, null);
                        dragV = v;
                        dragDX = x - v.location.x;
                        dragDY = y - v.location.y;
                        dragMoved = false;
                        preDrag = graph.copy();
                    } else {
                        setSelection(null, edgeAt(x, y));
                    }
                    break;
                case TOOL_REMOVE:
                    if (v != null) {
                        setSelection(v, null);
                        deleteSelection();
                    } else {
                        Edge ed = edgeAt(x, y);
                        if (ed != null) {
                            setSelection(null, ed);
                            deleteSelection();
                        }
                    }
                    break;
                default:
                    break;
            }
            canvas.repaint();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showContextMenu(e);
                return;
            }
            if (selectedTool == TOOL_EDGE && edgeFrom != null) {
                Vertex from = edgeFrom;
                Vertex to = vertexAt(e.getX(), e.getY());
                edgeFrom = null;
                if (to == null) {
                    setMessage("Edge cancelled \u2014 release over another vertex to connect");
                } else if (to != from) {
                    createEdge(from, to);
                } else if (edgeLeft) {
                    createEdge(from, from);
                }
            } else if (selectedTool == TOOL_MOVE && dragV != null) {
                if (dragMoved) {
                    positionsChanged();
                }
                dragV = null;
                preDrag = null;
            }
            hoverV = vertexAt(e.getX(), e.getY());
            hoverE = (hoverV == null) ? edgeAt(e.getX(), e.getY()) : null;
            updateCursor();
            updateStatusBar();
            canvas.repaint();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            cursorPt = e.getPoint();
            if (selectedTool == TOOL_EDGE && edgeFrom != null) {
                hoverV = vertexAt(e.getX(), e.getY());
                if (hoverV != edgeFrom) {
                    edgeLeft = true;
                }
                updateStatusBar();
            } else if (selectedTool == TOOL_MOVE && dragV != null) {
                if (!dragMoved) {
                    pushUndo(preDrag);
                    dragMoved = true;
                    updateActions();
                }
                dragV.location.x = clamp(e.getX() - dragDX, VERTEX_RADIUS, Math.max(VERTEX_RADIUS, canvas.getWidth() - VERTEX_RADIUS));
                dragV.location.y = clamp(e.getY() - dragDY, VERTEX_RADIUS, Math.max(VERTEX_RADIUS, canvas.getHeight() - VERTEX_RADIUS));
            }
            canvas.repaint();
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            hoverV = vertexAt(e.getX(), e.getY());
            hoverE = (hoverV == null) ? edgeAt(e.getX(), e.getY()) : null;
            updateCursor();
            updateStatusBar();
            canvas.repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (edgeFrom == null && dragV == null) {
                hoverV = null;
                hoverE = null;
                updateStatusBar();
                canvas.repaint();
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e) || selectedTool == TOOL_REMOVE) {
                return;
            }
            Vertex v = vertexAt(e.getX(), e.getY());
            if (v != null) {
                if (selectedTool == TOOL_MOVE) {
                    renameVertex(v);
                }
            } else {
                Edge ed = edgeAt(e.getX(), e.getY());
                if (ed != null) {
                    setEdgeWeight(ed);
                }
            }
        }
    }

    private void syncFlags() {
        boolean removing = (selectedTool == TOOL_REMOVE);
        for (Vertex v : graph.vertices) {
            v.wasFocused = (v == hoverV);
            v.wasClicked = (v == selV || v == edgeFrom);
            v.danger = removing && v == hoverV;
        }
        for (Edge ed : graph.edges) {
            ed.wasFocused = (ed == hoverE);
            ed.wasClicked = (ed == selE);
            ed.danger = removing && ed == hoverE;
        }
    }

    private class CanvasPane extends JPanel {

        CanvasPane() {
            setBackground(backgroundColour);
            setOpaque(true);
            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            syncFlags();
            graph.layoutEdges();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean bridges = rightTabs != null && rightTabs.getSelectedIndex() == TAB_OVERVIEW;

            for (Edge ed : graph.edges) {
                ed.draw(g2, graph.directed, bridges);
            }
            drawEdgePreview(g2);
            for (Vertex v : graph.vertices) {
                v.draw(g2);
            }
            if (graph.weighted) {
                for (Edge ed : graph.edges) {
                    ed.drawLabel(g2, bridges);
                }
            }
            drawOverlays(g2, bridges);
            g2.dispose();
        }

        private void drawEdgePreview(Graphics2D g2) {
            if (edgeFrom == null || cursorPt == null) {
                return;
            }
            Point end = cursorPt;
            Color c = Ui.ACCENT;
            if (hoverV != null && hoverV != edgeFrom) {
                end = hoverV.location;
                if (edgeBlockedReason(edgeFrom, hoverV) != null) {
                    c = Ui.DANGER;
                }
            }
            Stroke old = g2.getStroke();
            g2.setColor(c);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
            g2.drawLine(edgeFrom.location.x, edgeFrom.location.y, end.x, end.y);
            g2.setStroke(old);
        }

        private void drawOverlays(Graphics2D g2, boolean bridges) {
            if (graph.vertices.isEmpty()) {
                String msg = (selectedTool == TOOL_VERTEX) ? "Click anywhere to add a vertex"
                        : "No vertices yet \u2014 press V, then click to add one";
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 15f));
                g2.setColor(Ui.FAINT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
                return;
            }
            boolean anyCut = false, anyBridge = false, pair = false;
            for (Vertex v : graph.vertices) {
                anyCut |= v.isCutVertex;
                pair |= v.role != 0;
            }
            for (Edge ed : graph.edges) {
                anyBridge |= ed.isBridge;
            }
            java.util.List<Object[]> items = new java.util.ArrayList<Object[]>();
            if (anyCut) {
                items.add(new Object[]{Ui.WARN, "Cutpoint"});
            }
            if (anyBridge && bridges) {
                items.add(new Object[]{Ui.WARN, "Bridge"});
            }
            if (pair) {
                items.add(new Object[]{Ui.GOOD, "Pair: from"});
                items.add(new Object[]{Ui.PURPLE, "Pair: to"});
                items.add(new Object[]{Ui.TEAL, "Geodesic"});
            }
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
            FontMetrics fm = g2.getFontMetrics();
            int x = 12, y = getHeight() - 12;
            for (Object[] it : items) {
                g2.setColor((Color) it[0]);
                g2.fillOval(x, y - 10, 11, 11);
                g2.setColor(Ui.MUTED);
                String s = (String) it[1];
                g2.drawString(s, x + 16, y);
                x += 16 + fm.stringWidth(s) + 14;
            }
        }
    }
}