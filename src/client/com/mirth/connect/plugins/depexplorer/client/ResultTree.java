package com.mirth.connect.plugins.depexplorer.client;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;

import org.jdesktop.swingx.JXTreeTable;
import org.jdesktop.swingx.decorator.HighlighterFactory;
import org.jdesktop.swingx.treetable.AbstractTreeTableModel;

import com.mirth.connect.plugins.depexplorer.DepRef;

/**
 * Results as a collapsible tree (group › item › place) with columns for the relation, line and code, and a
 * detail pane for the selected row with the actions (open in Mirth, show dependencies, search the name).
 */
final class ResultTree extends JPanel {

    private static final long serialVersionUID = 1L;

    /** How rows are grouped and labelled. */
    interface Grouping {
        String level1(DepRef r);

        Icon icon1(String group, List<DepRef> rows);

        /** Second level (null: the rows sit directly under the group). */
        String level2(DepRef r);

        Icon icon2(DepRef r);

        String leaf(DepRef r);

        Icon leafIcon(DepRef r);

        String how(DepRef r);
    }

    /** What the row actions do. */
    interface Actions {
        void open(DepRef r);

        boolean canOpen(DepRef r);

        void exploreChannel(String channelId);

        boolean isCurrentChannel(String channelId);

        void search(DepRef r);

        /** A label for the search action, or null when there is nothing to search. */
        String searchLabel(DepRef r);
    }

    static final class Node {
        String label;
        Icon icon;
        DepRef ref;
        List<Node> kids = new ArrayList<Node>();
        int leaves;
        boolean group;
        int level;

        @Override
        public String toString() {
            return label;
        }
    }

    private final Actions actions;
    private final String howTitle;
    private final JXTreeTable table;
    private final Model model = new Model();
    private final JLabel detailTitle = new JLabel();
    private final JTextArea detailMessage = Ui.wrapText();
    private final JLabel detailWhere = new JLabel();
    private final JTextArea detailCode = new JTextArea();
    private final JButton openButton = Ui.button("Open in Mirth", "page_edit.png", "Open the place in the Mirth editor (double-click does the same).");
    private final JButton channelButton = Ui.button("Channel dependencies", "application_form.png", "Show everything this channel depends on and what depends on it.");
    private final JButton searchButton = Ui.button("Search", "folder_explore.png", "Show every place where this name is used.");
    private final JPanel detail = new JPanel(new BorderLayout(0, 6));
    private final JLabel empty = new JLabel("", SwingConstants.CENTER);
    private final JPanel center = new JPanel(new BorderLayout());
    private List<DepRef> rows = new ArrayList<DepRef>();
    private Grouping grouping;
    private String filter = "";
    private DepRef selected;
    private String channelOfSelection;

    ResultTree(String howTitle, Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        this.howTitle = howTitle;
        table = new JXTreeTable(model);
        table.setRootVisible(false);
        table.setShowsRootHandles(true);
        table.setRowHeight(Math.max(22, Ui.font().getSize() + 10));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setHighlighters(HighlighterFactory.createSimpleStriping(new Color(0xF4, 0xF6, 0xF9)));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setColumnControlVisible(false);
        table.setAutoCreateColumnsFromModel(false);
        table.setTreeCellRenderer(new TreeRenderer());
        table.setDefaultRenderer(Object.class, new CellRenderer());
        widths();
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail(selectedNode());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    Node n = selectedNode();
                    if (n != null && n.ref != null && actions.canOpen(n.ref)) {
                        actions.open(n.ref);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    Node n = selectedNode();
                    if (n != null && n.ref != null && actions.canOpen(n.ref)) {
                        actions.open(n.ref);
                        e.consume();
                    }
                }
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Ui.BORDER));
        empty.setForeground(Ui.MUTED);
        empty.setFont(Ui.font().deriveFont(13f));
        center.add(sp, BorderLayout.CENTER);

        // ---- detail pane
        detailTitle.setFont(Ui.bold(Ui.font().getSize() + 1f));
        detailWhere.setForeground(Ui.MUTED);
        detailCode.setEditable(false);
        detailCode.setFont(Ui.mono());
        detailCode.setBackground(Ui.CODE_BG);
        detailCode.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.BORDER), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        detailCode.setLineWrap(true);
        JPanel text = new JPanel();
        text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
        text.setOpaque(false);
        for (Component c : new Component[] { detailTitle, detailWhere, detailMessage, detailCode }) {
            ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(c);
            text.add(javax.swing.Box.createVerticalStrut(5));
        }
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(openButton);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(channelButton);
        buttons.add(javax.swing.Box.createHorizontalStrut(6));
        buttons.add(searchButton);
        openButton.addActionListener(e -> {
            if (selected != null) {
                actions.open(selected);
            }
        });
        channelButton.addActionListener(e -> {
            if (channelOfSelection != null) {
                actions.exploreChannel(channelOfSelection);
            }
        });
        searchButton.addActionListener(e -> {
            if (selected != null) {
                actions.search(selected);
            }
        });
        detail.setBackground(Ui.PANEL_BG);
        Ui.pad(detail, 8, 10, 8, 10);
        detail.add(text, BorderLayout.CENTER);
        detail.add(buttons, BorderLayout.SOUTH);
        showDetail(null);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, detail);
        split.setResizeWeight(1.0);
        split.setBorder(null);
        split.setDividerSize(5);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean done;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!done && getHeight() > 200) {
                    done = true;
                    split.setDividerLocation(Math.max(150, getHeight() - 175));
                }
            }
        });
    }

    private void widths() {
        javax.swing.table.TableColumnModel cm = table.getColumnModel();
        if (cm.getColumnCount() < 4) {
            return;
        }
        cm.getColumn(0).setPreferredWidth(560);
        cm.getColumn(1).setPreferredWidth(190);
        cm.getColumn(2).setPreferredWidth(48);
        cm.getColumn(2).setMaxWidth(64);
        cm.getColumn(2).setMinWidth(36);
        cm.getColumn(3).setPreferredWidth(520);
        DefaultTableCellRenderer right = new CellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        cm.getColumn(2).setCellRenderer(right);
    }

    void setEmptyText(String s) {
        empty.setText("<html><div style='text-align:center'>" + s + "</div></html>");
    }

    void setRows(List<DepRef> rows, Grouping grouping) {
        this.rows = rows == null ? new ArrayList<DepRef>() : rows;
        this.grouping = grouping;
        rebuild();
    }

    void setFilter(String f) {
        filter = f == null ? "" : f.trim().toLowerCase();
        rebuild();
    }

    int visibleCount() {
        return model.root.leaves;
    }

    private void rebuild() {
        Node root = new Node();
        Map<String, Node> l1 = new LinkedHashMap<String, Node>();
        Map<String, List<DepRef>> l1rows = new LinkedHashMap<String, List<DepRef>>();
        Map<String, Node> l2 = new LinkedHashMap<String, Node>();
        for (DepRef r : rows) {
            if (!filter.isEmpty() && !matches(r)) {
                continue;
            }
            String g1 = grouping.level1(r);
            Node n1 = l1.get(g1);
            if (n1 == null) {
                n1 = new Node();
                n1.label = g1;
                n1.group = true;
                n1.level = 1;
                l1.put(g1, n1);
                l1rows.put(g1, new ArrayList<DepRef>());
                root.kids.add(n1);
            }
            l1rows.get(g1).add(r);
            Node parent = n1;
            String g2 = grouping.level2(r);
            if (g2 != null) {
                String k = g1 + "\u0000" + g2;
                Node n2 = l2.get(k);
                if (n2 == null) {
                    n2 = new Node();
                    n2.label = g2;
                    n2.icon = grouping.icon2(r);
                    n2.group = true;
                    n2.level = 2;
                    n2.ref = r;
                    l2.put(k, n2);
                    n1.kids.add(n2);
                }
                n2.leaves++;
                parent = n2;
            }
            Node leaf = new Node();
            leaf.ref = r;
            leaf.label = grouping.leaf(r);
            leaf.icon = grouping.leafIcon(r);
            leaf.level = parent.level + 1;
            parent.kids.add(leaf);
            n1.leaves++;
            root.leaves++;
        }
        for (Map.Entry<String, Node> e : l1.entrySet()) {
            e.getValue().icon = grouping.icon1(e.getKey(), l1rows.get(e.getKey()));
        }
        // a second-level group with a single place: one row instead of two
        for (Node n2 : l2.values()) {
            if (n2.kids.size() == 1) {
                Node only = n2.kids.get(0);
                n2.ref = only.ref;
                n2.group = false;
                n2.kids.clear();
                if (only.label != null && only.label.startsWith(n2.label)) {
                    n2.label = only.label;
                } else if (only.label != null && !only.label.equals(n2.label) && !"Channel".equals(only.label)) {
                    n2.label = n2.label + "   ›   " + only.label;
                }
            }
        }
        model.setRoot(root);
        center.removeAll();
        if (root.leaves == 0) {
            center.add(empty, BorderLayout.CENTER);
        } else {
            JScrollPane sp = new JScrollPane(table);
            sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Ui.BORDER));
            center.add(sp, BorderLayout.CENTER);
        }
        center.revalidate();
        center.repaint();
        // expand groups; second-level groups too when the result is small
        for (int i = 0; i < table.getRowCount(); i++) {
            TreePath p = table.getPathForRow(i);
            Node n = p == null ? null : (Node) p.getLastPathComponent();
            if (n != null && n.group && ((n.level == 1 && (n.leaves <= 60 || root.kids.size() == 1)) || (n.level == 2 && root.leaves <= 150))) {
                table.expandRow(i);
            }
        }
        showDetail(null);
    }

    private boolean matches(DepRef r) {
        for (String s : new String[] { r.ownerName, r.location, r.key, r.message, r.snippet, r.category, r.relation, r.targetName }) {
            if (s != null && s.toLowerCase().contains(filter)) {
                return true;
            }
        }
        return false;
    }

    private Node selectedNode() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        TreePath p = table.getPathForRow(row);
        return p == null ? null : (Node) p.getLastPathComponent();
    }

    private void showDetail(Node n) {
        DepRef r = n == null ? null : n.ref;
        if (n != null && n.group && n.level == 2 && n.kids.size() == 1) {
            r = n.kids.get(0).ref;
        } else if (n != null && n.group) {
            r = null;
        }
        selected = r;
        if (r == null) {
            detailTitle.setIcon(null);
            detailTitle.setText(n == null ? "Select a row to see the details." : n.label + "  (" + Ui.plural(n.leaves, "place") + ")");
            detailWhere.setText(" ");
            detailMessage.setText("");
            detailCode.setVisible(false);
            openButton.setEnabled(false);
            channelButton.setVisible(false);
            searchButton.setVisible(false);
            return;
        }
        detailTitle.setIcon(r.severity != null ? Ui.severityIcon(r.severity) : Ui.typeIcon(r.ownerType));
        String title = r.category != null ? r.category : r.relation;
        if (r.key != null && r.category != null) {
            title += ": " + r.key;
        } else if (r.key != null && !Q_TEXT.equals(r.relation)) {
            title += (title == null ? "" : " · ") + r.key;
        }
        detailTitle.setText(title);
        String where = r.ownerType + " '" + r.ownerName + "'";
        if (r.location != null && !r.location.equals("Channel") && !r.location.equals(r.ownerName)) {
            where += "  ›  " + r.location;
        }
        if (r.line > 0) {
            where += "  (line " + r.line + ")";
        }
        detailWhere.setText(where);
        String msg = r.message == null ? "" : r.message;
        if (r.targetName != null && (r.message == null || !r.message.contains(r.targetName))) {
            msg += (msg.isEmpty() ? "" : "  ") + "→ " + r.targetType + " '" + r.targetName + "'";
        }
        detailMessage.setText(msg);
        detailMessage.setVisible(!msg.isEmpty());
        boolean code = r.snippet != null && !r.snippet.isEmpty();
        detailCode.setVisible(code);
        detailCode.setText(code ? (r.line > 0 ? r.line + " │ " : "") + r.snippet : "");
        openButton.setEnabled(actions.canOpen(r));
        channelOfSelection = T_CHANNEL.equals(r.targetType) && r.targetId != null ? r.targetId : T_CHANNEL.equals(r.ownerType) ? r.ownerId : null;
        if (channelOfSelection != null && actions.isCurrentChannel(channelOfSelection)) {
            channelOfSelection = null;
        }
        channelButton.setVisible(channelOfSelection != null);
        if (channelOfSelection != null) {
            String name = T_CHANNEL.equals(r.targetType) && r.targetId != null ? r.targetName : r.ownerName;
            channelButton.setText("Dependencies of '" + shorten(name, 40) + "'");
        }
        String sl = actions.searchLabel(r);
        searchButton.setVisible(sl != null);
        if (sl != null) {
            searchButton.setText(sl);
        }
        detail.revalidate();
    }

    private static String shorten(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        Node n = selectedNode();
        JPopupMenu m = new JPopupMenu();
        if (n != null && n.ref != null && !n.group) {
            final DepRef r = n.ref;
            JMenuItem open = new JMenuItem("Open in Mirth", Ui.icon("page_edit.png"));
            open.setEnabled(actions.canOpen(r));
            open.addActionListener(a -> actions.open(r));
            m.add(open);
            if (channelOfSelection != null) {
                final String id = channelOfSelection;
                JMenuItem ch = new JMenuItem(channelButton.getText(), Ui.icon("application_form.png"));
                ch.addActionListener(a -> actions.exploreChannel(id));
                m.add(ch);
            }
            String sl = actions.searchLabel(r);
            if (sl != null) {
                JMenuItem s = new JMenuItem(sl, Ui.icon("folder_explore.png"));
                s.addActionListener(a -> actions.search(r));
                m.add(s);
            }
            m.addSeparator();
            JMenuItem copy = new JMenuItem("Copy location");
            copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(r.ownerType + " '" + r.ownerName + "' › " + r.location + (r.line > 0 ? " (line " + r.line + ")" : "")), null));
            m.add(copy);
        }
        JMenuItem expand = new JMenuItem("Expand all");
        expand.addActionListener(a -> table.expandAll());
        JMenuItem collapse = new JMenuItem("Collapse all");
        collapse.addActionListener(a -> table.collapseAll());
        m.add(expand);
        m.add(collapse);
        m.show(e.getComponent(), e.getX(), e.getY());
    }

    // =====================================================================

    private final class Model extends AbstractTreeTableModel {
        Node root = new Node();

        Model() {
            super(new Node());
        }

        void setRoot(Node r) {
            root = r;
            this.root = r;
            modelSupport.fireNewRoot();
        }

        @Override
        public Object getRoot() {
            return root;
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int c) {
            return c == 0 ? "Where" : c == 1 ? howTitle : c == 2 ? "Line" : "Code";
        }

        @Override
        public Object getValueAt(Object node, int c) {
            Node n = (Node) node;
            if (c == 0) {
                return n;
            }
            if (n.group && !(n.level == 2 && n.kids.size() == 1)) {
                return c == 1 && n.level == 1 ? "" : "";
            }
            DepRef r = n.group ? n.kids.get(0).ref : n.ref;
            if (r == null) {
                return "";
            }
            if (c == 1) {
                return grouping.how(r);
            }
            if (c == 2) {
                return r.line > 0 ? String.valueOf(r.line) : "";
            }
            return r.snippet == null ? "" : r.snippet;
        }

        @Override
        public Object getChild(Object parent, int index) {
            return ((Node) parent).kids.get(index);
        }

        @Override
        public int getChildCount(Object parent) {
            return ((Node) parent).kids.size();
        }

        @Override
        public int getIndexOfChild(Object parent, Object child) {
            return ((Node) parent).kids.indexOf(child);
        }

        @Override
        public boolean isLeaf(Object node) {
            return ((Node) node).kids.isEmpty();
        }
    }

    private static final class TreeRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        TreeRenderer() {
            setBackgroundNonSelectionColor(null);
            setBorderSelectionColor(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, false);
            Node n = (Node) value;
            setIcon(n.icon);
            if (n.group) {
                setText("<html>" + (n.level == 1 ? "<b>" : "") + Ui.html(n.label) + (n.level == 1 ? "</b>" : "") + " <font color='#6B7280'>("
                        + n.leaves + ")</font></html>");
            } else {
                setText(n.label);
            }
            setOpaque(false);
            return this;
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, false, row, col);
            setFont(col == 3 ? Ui.mono() : Ui.font());
            if (!sel) {
                setForeground(col == 3 ? new Color(0x37, 0x41, 0x51) : col == 2 ? Ui.MUTED : t.getForeground());
            }
            setToolTipText(col == 3 && value != null && value.toString().length() > 60 ? value.toString() : null);
            setBorder(BorderFactory.createEmptyBorder(0, col == 3 ? 10 : 4, 0, col == 2 ? 6 : 4));
            return this;
        }
    }
}
