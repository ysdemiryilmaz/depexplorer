package com.mirth.connect.plugins.depexplorer.client;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.mirth.connect.plugins.depexplorer.DepEdge;
import com.mirth.connect.plugins.depexplorer.DepNode;
import com.mirth.connect.plugins.depexplorer.DepOverview;
import com.mirth.connect.plugins.depexplorer.DepQuery;
import com.mirth.connect.plugins.depexplorer.DepRef;
import com.mirth.connect.plugins.depexplorer.DepReport;
import com.mirth.connect.plugins.depexplorer.DepResult;

/**
 * The Dependency Explorer: one search box for everything, the results as a tree, the channel flow graph and
 * the broken references / unused items reports. Used full-screen under Settings and, for one channel, in the
 * "View Dependencies" window.
 */
final class ExplorerView extends JPanel implements ResultTree.Actions, Api.Busy {

    private static final long serialVersionUID = 1L;
    private static final String[] TYPE_LABELS = { "Everything", "Channels", "Configuration Map keys", "Code template functions", "Map variables", "Text" };
    private static final String[] TYPE_KEYS = { null, Q_CHANNEL, Q_CONFIG, Q_FUNCTION, Q_MAPVAR, Q_TEXT };

    private final boolean channelWindow;
    private final SuggestField search = new SuggestField();
    private final JComboBox<String> type = new JComboBox<String>(TYPE_LABELS);
    private final JLabel status = Ui.label(" ", Ui.font().deriveFont(11.5f), Ui.MUTED);
    private final JProgressBar progress = new JProgressBar();
    private final JTabbedPane tabs = new JTabbedPane();

    // explore
    private final CardLayout exploreCards = new CardLayout();
    private final JPanel explore = new JPanel(exploreCards);
    private final JPanel welcome = new JPanel(new BorderLayout());
    private final ResultTree results = new ResultTree("How", this);
    private final JLabel resultBadge = new JLabel();
    private final JLabel resultTitle = new JLabel();
    private final JLabel resultSubtitle = new JLabel();
    private final JButton back = Ui.arrowButton(true, "Back");
    private final JButton forward = Ui.arrowButton(false, "Forward");
    private final JButton showInGraph = Ui.button("Show in flow graph", "world_link.png", "Show this channel and its neighbours in the flow graph.");
    private final JButton openChannel = Ui.button("Open channel", "page_edit.png", "Open the channel in the channel editor.");
    private final List<DepQuery> history = new ArrayList<DepQuery>();
    private int historyIndex = -1;
    private DepResult current;

    // graph
    private final FlowGraph graph = new FlowGraph();
    private final JComboBox<Object> focus = new JComboBox<Object>();
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(2, 1, 9, 1));
    private boolean updatingFocus;

    // reports
    private final ResultTree problems = new ResultTree("Name", this);
    private final ResultTree unused = new ResultTree("Name", this);
    private final JTextField problemFilter = new JTextField(22);
    private final JTextField unusedFilter = new JTextField(22);
    private final JCheckBox showErrors = new JCheckBox("Errors", true);
    private final JCheckBox showWarnings = new JCheckBox("Warnings", true);

    private DepOverview overview;
    private DepReport report;
    private int busy;
    private long loadedAt;
    private String pendingChannel;

    ExplorerView(boolean channelWindow) {
        super(new BorderLayout());
        this.channelWindow = channelWindow;
        setBackground(Color.WHITE);
        add(header(), BorderLayout.NORTH);

        // ---- Explore tab
        explore.add(welcome, "welcome");
        JPanel res = new JPanel(new BorderLayout());
        res.setOpaque(false);
        res.add(resultHeader(), BorderLayout.NORTH);
        res.add(results, BorderLayout.CENTER);
        explore.add(res, "results");
        results.setEmptyText("Nothing uses it.");
        tabs.addTab("Explore", Ui.icon("folder_explore.png"), explore);
        tabs.addTab("Flow Graph", Ui.icon("world_link.png"), graphPanel());
        if (!channelWindow) {
            tabs.addTab("Problems", Ui.icon("exclamation.png"), reportPanel(problems, problemFilter, true));
            tabs.addTab("Unused", Ui.icon("information.png"), reportPanel(unused, unusedFilter, false));
        }
        add(tabs, BorderLayout.CENTER);
        problems.setEmptyText("No broken references found.");
        unused.setEmptyText("Nothing unused found.");
        showWelcome();
        updateHistoryButtons();
    }

    // =====================================================================
    // Layout pieces
    // =====================================================================

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(Ui.PANEL_BG);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.BORDER), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        type.setFocusable(false);
        type.setMaximumSize(type.getPreferredSize());
        type.addActionListener(e -> search.setTypeFilter(TYPE_KEYS[type.getSelectedIndex()]));
        search.setPlaceholder("Search a channel, Configuration Map key, function, map variable or any text…");
        search.setChosen(it -> query(it.type, it.key, true));
        search.setMaximumSize(new Dimension(Short.MAX_VALUE, search.getPreferredSize().height));
        JButton go = Ui.button("Search", "folder_explore.png", "Search (Enter)");
        go.addActionListener(e -> {
            String t = search.getText().trim();
            if (!t.isEmpty()) {
                String k = TYPE_KEYS[type.getSelectedIndex()];
                SuggestField.Item exact = null;
                for (SuggestField.Item it : items()) {
                    if ((k == null || k.equals(it.type)) && it.key.equalsIgnoreCase(t)) {
                        exact = it;
                        break;
                    }
                }
                if (exact != null) {
                    query(exact.type, exact.key, true);
                } else {
                    query(k == null ? Q_TEXT : k, t, true);
                }
            }
        });
        left.add(type);
        left.add(Box.createHorizontalStrut(6));
        left.add(search);
        left.add(Box.createHorizontalStrut(6));
        left.add(go);
        p.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        progress.setIndeterminate(true);
        progress.setPreferredSize(new Dimension(70, 12));
        progress.setVisible(false);
        JButton rescan = Ui.button("Rescan", "arrow_refresh.png",
                "Read every channel, code template and script again. (Changes are normally picked up automatically.)");
        rescan.addActionListener(e -> refresh(true));
        right.add(progress);
        right.add(status);
        right.add(rescan);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JComponent resultHeader() {
        JPanel p = new JPanel(new BorderLayout(8, 2));
        p.setBackground(Color.WHITE);
        Ui.pad(p, 10, 10, 8, 10);
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        nav.setOpaque(false);
        back.addActionListener(e -> go(-1));
        forward.addActionListener(e -> go(1));
        nav.add(back);
        nav.add(forward);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        line.setOpaque(false);
        resultBadge.setOpaque(true);
        resultBadge.setForeground(Color.WHITE);
        resultBadge.setFont(Ui.bold(10.5f));
        resultBadge.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        resultTitle.setFont(Ui.bold(16f));
        line.add(resultBadge);
        line.add(resultTitle);
        resultSubtitle.setForeground(Ui.MUTED);
        resultSubtitle.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 0));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        titles.add(line);
        titles.add(resultSubtitle);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        showInGraph.addActionListener(e -> {
            if (current != null && Q_CHANNEL.equals(current.type)) {
                showInGraph(current.key);
            }
        });
        openChannel.addActionListener(e -> {
            if (current != null && Q_CHANNEL.equals(current.type)) {
                Navigator.openChannel(this, current.key, null);
            }
        });
        actions.add(openChannel);
        actions.add(showInGraph);
        p.add(nav, BorderLayout.WEST);
        p.add(titles, BorderLayout.CENTER);
        p.add(actions, BorderLayout.EAST);
        return p;
    }

    private JComponent graphPanel() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(Ui.PANEL_BG);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.BORDER));
        focus.setFocusable(false);
        focus.setMaximumRowCount(25);
        focus.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v instanceof DepNode ? ((DepNode) v).name : v, i, s, f);
                return this;
            }
        });
        focus.addActionListener(e -> {
            if (updatingFocus) {
                return;
            }
            Object v = focus.getSelectedItem();
            graph.focus = v instanceof DepNode ? ((DepNode) v).id : null;
            depth.setEnabled(graph.focus != null);
            graph.rebuild();
        });
        depth.addChangeListener(e -> {
            graph.depth = (Integer) depth.getValue();
            if (graph.focus != null) {
                graph.rebuild();
            }
        });
        ((JSpinner.DefaultEditor) depth.getEditor()).getTextField().setColumns(2);
        bar.add(Ui.label("Show:", null, null));
        bar.add(focus);
        bar.add(Ui.label("Steps:", null, null));
        depth.setToolTipText("How many links away from the chosen channel to show.");
        bar.add(depth);
        bar.add(Box.createHorizontalStrut(6));
        final JCheckBox deploy = check("Deploy dependencies", graph.showDeploy, "Channel › Set Dependencies (deploy / start order).");
        final JCheckBox shared = check("Shared globalMap", graph.showGlobalMap, "One channel writes a globalMap variable that another channel reads.");
        final JCheckBox mention = check("Channel ids in code", graph.showMentions, "A channel id appears in a script or setting (e.g. a variable used with router.routeMessageByChannelId).");
        final JCheckBox single = check("Unconnected channels", graph.showUnconnected, "Also show channels that have no links.");
        deploy.addActionListener(e -> {
            graph.showDeploy = deploy.isSelected();
            graph.rebuild();
        });
        shared.addActionListener(e -> {
            graph.showGlobalMap = shared.isSelected();
            graph.rebuild();
        });
        mention.addActionListener(e -> {
            graph.showMentions = mention.isSelected();
            graph.rebuild();
        });
        single.addActionListener(e -> {
            graph.showUnconnected = single.isSelected();
            graph.rebuild();
        });
        bar.add(deploy);
        bar.add(shared);
        bar.add(mention);
        bar.add(single);
        JButton zin = Ui.button("", "add.png", "Zoom in (mouse wheel)");
        JButton zout = Ui.button("", "delete.png", "Zoom out (mouse wheel)");
        JButton fit = Ui.button("Fit", "arrow_rotate_clockwise.png", "Fit the graph to the window.");
        zin.addActionListener(e -> graph.zoom(1.25));
        zout.addActionListener(e -> graph.zoom(1 / 1.25));
        fit.addActionListener(e -> graph.fit());
        bar.add(Box.createHorizontalStrut(6));
        bar.add(zout);
        bar.add(zin);
        bar.add(fit);
        p.add(bar, BorderLayout.NORTH);
        p.add(graph, BorderLayout.CENTER);
        depth.setEnabled(false);
        graph.setListener(new FlowGraph.Listener() {
            @Override
            public void selected(String channelId) {
                DepNode n = node(channelId);
                status.setText(n == null ? statusText() : n.name + " – double-click for its dependencies, right-click for more");
            }

            @Override
            public void explore(String channelId) {
                exploreChannel(channelId);
            }

            @Override
            public void popup(final String channelId, MouseEvent e) {
                DepNode n = node(channelId);
                JPopupMenu m = new JPopupMenu();
                JMenuItem a = new JMenuItem("Show dependencies of '" + (n == null ? "" : n.name) + "'", Ui.icon("folder_explore.png"));
                a.addActionListener(x -> exploreChannel(channelId));
                JMenuItem b = new JMenuItem("Center the graph on this channel", Ui.icon("world_link.png"));
                b.addActionListener(x -> showInGraph(channelId));
                JMenuItem c = new JMenuItem("Open channel in the editor", Ui.icon("page_edit.png"));
                c.addActionListener(x -> Navigator.openChannel(ExplorerView.this, channelId, null));
                m.add(a);
                m.add(b);
                m.add(c);
                if (graph.focus != null) {
                    JMenuItem d = new JMenuItem("Show all connected channels");
                    d.addActionListener(x -> setFocus(null));
                    m.addSeparator();
                    m.add(d);
                }
                m.show(e.getComponent(), e.getX(), e.getY());
            }
        });
        return p;
    }

    private static JCheckBox check(String text, boolean on, String tip) {
        JCheckBox c = new JCheckBox(text, on);
        c.setOpaque(false);
        c.setFocusable(false);
        c.setToolTipText(tip);
        return c;
    }

    private JComponent reportPanel(final ResultTree tree, final JTextField filter, boolean problemsTab) {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(Ui.PANEL_BG);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.BORDER));
        bar.add(Ui.label("Filter:", null, null));
        filter.setToolTipText("Show only rows containing this text (channel, name, location, code).");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                tree.setFilter(filter.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                tree.setFilter(filter.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                tree.setFilter(filter.getText());
            }
        });
        bar.add(filter);
        if (problemsTab) {
            showErrors.setOpaque(false);
            showWarnings.setOpaque(false);
            showErrors.setIcon(null);
            showErrors.addActionListener(e -> showReport());
            showWarnings.addActionListener(e -> showReport());
            bar.add(showErrors);
            bar.add(showWarnings);
            bar.add(Ui.label("Errors fail at runtime (missing key, channel, function); warnings may be fine (e.g. a variable set outside Mirth).", Ui.font().deriveFont(11f),
                    Ui.MUTED));
        } else {
            bar.add(Ui.label("Candidates for clean-up. Check before deleting: some names may be used outside Mirth or built at runtime.", Ui.font().deriveFont(11f),
                    Ui.MUTED));
        }
        p.add(bar, BorderLayout.NORTH);
        p.add(tree, BorderLayout.CENTER);
        return p;
    }

    // =====================================================================
    // Loading
    // =====================================================================

    @Override
    public void busy(boolean on, String what) {
        busy += on ? 1 : -1;
        progress.setVisible(busy > 0);
        setCursor(busy > 0 ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        if (on && what != null) {
            status.setText(what + "…");
        } else if (busy == 0) {
            status.setText(statusText());
        }
    }

    /** Loads the overview (and the reports); quietly skipped when loaded very recently. */
    void refresh(final boolean force) {
        if (!force && overview != null && System.currentTimeMillis() - loadedAt < 4000) {
            return;
        }
        Api.run(this, this, overview == null ? "Reading channels, code templates and scripts" : "Checking for changes", () -> Api.servlet().getOverview(force),
                o -> {
                    overview = o;
                    loadedAt = System.currentTimeMillis();
                    applyOverview();
                    if (!channelWindow) {
                        Api.run(this, this, "Checking references", () -> Api.servlet().getReport(), r -> {
                            report = r;
                            showReport();
                        });
                    }
                    if (pendingChannel != null) {
                        String id = pendingChannel;
                        pendingChannel = null;
                        exploreChannel(id);
                        depth.setValue(1);
                        setFocus(id);
                    } else if (current != null && force) {
                        rerun();
                    }
                });
    }

    /** Shows the dependencies of one channel (Channel Tasks › View Dependencies). */
    void startWithChannel(String channelId) {
        pendingChannel = channelId;
        refresh(true);
    }

    private void applyOverview() {
        search.setItems(items());
        graph.setOverview(overview);
        updatingFocus = true;
        Object sel = focus.getSelectedItem();
        DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<Object>();
        m.addElement("All connected channels");
        List<DepNode> sorted = new ArrayList<DepNode>(overview.channels);
        Collections.sort(sorted, new Comparator<DepNode>() {
            @Override
            public int compare(DepNode a, DepNode b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        for (DepNode n : sorted) {
            m.addElement(n);
        }
        focus.setModel(m);
        if (sel instanceof DepNode) {
            for (DepNode n : sorted) {
                if (n.id.equals(((DepNode) sel).id)) {
                    focus.setSelectedItem(n);
                }
            }
        }
        updatingFocus = false;
        status.setText(statusText());
        status.setToolTipText("<html>Server: last check took " + overview.scanMillis + " ms" + (overview.rescanned > 0 ? ", " + Ui.plural(overview.rescanned, "channel") + " read again" : "")
                + "<br>Memory kept on the server for the analysis: about " + Math.max(1, overview.cacheKb / 1024) + " MB<br>"
                + "Changes are picked up automatically; Rescan reads everything again.</html>");
        if (current == null) {
            showWelcome();
        }
    }

    private String statusText() {
        if (overview == null) {
            return " ";
        }
        int links = 0;
        for (DepEdge e : overview.edges) {
            if (!E_DEPLOY.equals(e.kind) && !E_GLOBALMAP.equals(e.kind)) {
                links++;
            }
        }
        return Ui.plural(overview.channels.size(), "channel") + " · " + Ui.plural(links, "message link") + " · checked "
                + new SimpleDateFormat("HH:mm:ss").format(new Date(overview.scannedAt));
    }

    List<SuggestField.Item> items() {
        List<SuggestField.Item> l = new ArrayList<SuggestField.Item>();
        if (overview == null) {
            return l;
        }
        for (DepNode n : overview.channels) {
            l.add(new SuggestField.Item(Q_CHANNEL, n.id, n.name, n.sourceType + (n.enabled ? "" : " · disabled")));
        }
        for (String k : overview.configKeys) {
            l.add(new SuggestField.Item(Q_CONFIG, k, k, "Configuration Map"));
        }
        if (overview.missingConfigKeys != null) {
            for (String k : overview.missingConfigKeys) {
                l.add(new SuggestField.Item(Q_CONFIG, k, k, "read, but not defined in the Configuration Map"));
            }
        }
        for (String f : overview.functions) {
            String[] p = f.split("\\|", -1);
            l.add(new SuggestField.Item(Q_FUNCTION, p[0], p[0] + "()", (p.length > 2 && !p[2].isEmpty() ? p[2] + " › " : "") + (p.length > 1 ? p[1] : "")));
        }
        for (String v : overview.mapVariables) {
            String[] p = v.split("\\|", -1);
            l.add(new SuggestField.Item(Q_MAPVAR, p[0], p[0], p.length > 1 ? p[1] : ""));
        }
        Collections.sort(l, new Comparator<SuggestField.Item>() {
            @Override
            public int compare(SuggestField.Item a, SuggestField.Item b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return l;
    }

    private DepNode node(String id) {
        if (overview == null || id == null) {
            return null;
        }
        for (DepNode n : overview.channels) {
            if (n.id.equals(id)) {
                return n;
            }
        }
        return null;
    }

    // =====================================================================
    // Welcome page
    // =====================================================================

    private void showWelcome() {
        welcome.removeAll();
        welcome.setBackground(Color.WHITE);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        Ui.pad(box, 24, 28, 24, 28);
        JLabel t = Ui.label(channelWindow ? "Loading…" : "What depends on what?", Ui.bold(18f), null);
        JLabel s = Ui.label("<html>Type the name of a channel, a Configuration Map key, a code template function or a map variable above "
                + "and pick it from the list – or press Enter to find any text in every script and connector setting.<br>"
                + "Double-click a result to open that exact place (connector, filter rule, transformer step, script line) in Mirth.</html>", Ui.font().deriveFont(12.5f),
                Ui.MUTED);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(t);
        box.add(Box.createVerticalStrut(8));
        box.add(s);
        box.add(Box.createVerticalStrut(18));
        if (overview != null) {
            JPanel tiles = new JPanel(new GridLayout(1, 0, 12, 0));
            tiles.setOpaque(false);
            tiles.setAlignmentX(Component.LEFT_ALIGNMENT);
            int links = 0;
            for (DepEdge e : overview.edges) {
                if (!E_DEPLOY.equals(e.kind) && !E_GLOBALMAP.equals(e.kind)) {
                    links++;
                }
            }
            tiles.add(tile(String.valueOf(overview.channels.size()), "channels", Ui.ACCENT, () -> tabs.setSelectedIndex(1)));
            tiles.add(tile(String.valueOf(links), "message links between channels", Ui.E_ROUTE_COLOR, () -> tabs.setSelectedIndex(1)));
            if (!channelWindow) {
                tiles.add(tile(String.valueOf(overview.errors), "errors (broken references)", overview.errors > 0 ? Ui.ERROR : Ui.OK, () -> tabs.setSelectedIndex(2)));
                tiles.add(tile(String.valueOf(overview.warnings), "warnings", overview.warnings > 0 ? Ui.WARNING : Ui.OK, () -> tabs.setSelectedIndex(2)));
                tiles.add(tile(String.valueOf(overview.unused), "unused items", Ui.INFO, () -> tabs.setSelectedIndex(3)));
            }
            tiles.setMaximumSize(new Dimension(Short.MAX_VALUE, 92));
            box.add(tiles);
            box.add(Box.createVerticalStrut(22));
            // most connected channels
            final Map<String, int[]> deg = new HashMap<String, int[]>();
            for (DepEdge e : overview.edges) {
                if (E_DEPLOY.equals(e.kind)) {
                    continue;
                }
                count(deg, e.fromId, 1);
                if (e.toId != null) {
                    count(deg, e.toId, 0);
                }
            }
            List<String> ids = new ArrayList<String>(deg.keySet());
            Collections.sort(ids, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int[] x = deg.get(a), y = deg.get(b);
                    return (y[0] + y[1]) - (x[0] + x[1]);
                }
            });
            if (!ids.isEmpty()) {
                JLabel h = Ui.label("Most connected channels", Ui.bold(13f), null);
                h.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.add(h);
                box.add(Box.createVerticalStrut(6));
                for (int i = 0; i < Math.min(8, ids.size()); i++) {
                    final DepNode n = node(ids.get(i));
                    if (n == null) {
                        continue;
                    }
                    int[] d = deg.get(n.id);
                    JLabel l = link(n.name, "  receives from " + d[0] + ", sends to " + d[1], () -> exploreChannel(n.id));
                    l.setAlignmentX(Component.LEFT_ALIGNMENT);
                    box.add(l);
                }
            }
        }
        JScrollPane sp = new JScrollPane(box);
        sp.setBorder(null);
        sp.getViewport().setBackground(Color.WHITE);
        welcome.add(sp, BorderLayout.CENTER);
        welcome.revalidate();
        welcome.repaint();
        exploreCards.show(explore, current == null ? "welcome" : "results");
    }

    private static void count(Map<String, int[]> m, String id, int idx) {
        int[] c = m.get(id);
        if (c == null) {
            c = new int[2];
            m.put(id, c);
        }
        c[idx]++;
    }

    private JComponent tile(String value, String text, Color c, final Runnable click) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 4, 1, 1, c), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        JLabel v = Ui.label(value, Ui.bold(24f), c);
        JLabel t = Ui.label(text, Ui.font().deriveFont(11.5f), Ui.MUTED);
        p.add(v, BorderLayout.CENTER);
        p.add(t, BorderLayout.SOUTH);
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        p.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                click.run();
            }
        });
        return p;
    }

    private static JLabel link(String text, String detail, final Runnable click) {
        JLabel l = new JLabel("<html><a href='#'>" + Ui.html(text) + "</a><font color='#6B7280'>" + Ui.html(detail) + "</font></html>");
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                click.run();
            }
        });
        return l;
    }

    // =====================================================================
    // Queries
    // =====================================================================

    void query(String qtype, String key, boolean record) {
        final DepQuery q = new DepQuery(qtype, key);
        if (record) {
            while (history.size() > historyIndex + 1) {
                history.remove(history.size() - 1);
            }
            history.add(q);
            historyIndex = history.size() - 1;
            if (history.size() > 50) {
                history.remove(0);
                historyIndex--;
            }
        }
        updateHistoryButtons();
        tabs.setSelectedIndex(0);
        Api.run(this, this, "Searching", () -> Api.servlet().query(q), this::showResult);
    }

    private void rerun() {
        if (current != null) {
            query(current.type, current.key, false);
        }
    }

    private void go(int d) {
        int i = historyIndex + d;
        if (i >= 0 && i < history.size()) {
            historyIndex = i;
            DepQuery q = history.get(i);
            query(q.type, q.key, false);
        }
    }

    private void updateHistoryButtons() {
        back.setEnabled(historyIndex > 0);
        forward.setEnabled(historyIndex >= 0 && historyIndex < history.size() - 1);
    }

    private void showResult(DepResult r) {
        current = r;
        resultBadge.setText(SuggestField.Item.typeName(r.type));
        resultBadge.setBackground(SuggestField.Item.typeColor(r.type));
        resultTitle.setText(Q_TEXT.equals(r.type) ? "“" + r.title + "”" : Q_FUNCTION.equals(r.type) ? r.title + "()" : r.title);
        int places = 0;
        int problemsCount = 0;
        for (DepRef x : r.rows) {
            if ("Problems".equals(x.group)) {
                problemsCount++;
            } else {
                places++;
            }
        }
        String sub = (r.subtitle == null ? "" : r.subtitle) + "  ·  " + Ui.plural(places, "place");
        if (problemsCount > 0) {
            sub += "  ·  " + Ui.plural(problemsCount, "problem");
        }
        if (r.truncated) {
            sub += "  ·  only the first " + r.rows.size() + " are shown";
        }
        resultSubtitle.setText(sub);
        boolean ch = Q_CHANNEL.equals(r.type);
        showInGraph.setVisible(ch);
        openChannel.setVisible(ch);
        if (!Q_TEXT.equals(r.type)) {
            search.setTextQuietly(Q_FUNCTION.equals(r.type) ? r.title + "()" : r.title);
        }
        results.setEmptyText(Q_TEXT.equals(r.type) ? "The text was not found in any channel, code template or global script."
                : "Nothing uses it – no channel, code template or global script refers to it.");
        results.setRows(sorted(r.rows, r.type), exploreGrouping(r.type));
        exploreCards.show(explore, "results");
        if (ch) {
            graph.setSelected(r.key);
        }
    }

    private static final String[] CHANNEL_GROUP_ORDER = { "Problems", "Receives messages from", "Sends messages to", "Deploy dependencies", "Shared map variables",
            "Configuration Map keys", "Code template functions", "Map variables written", "Map variables read" };

    private static List<DepRef> sorted(List<DepRef> rows, String qtype) {
        List<DepRef> l = new ArrayList<DepRef>(rows);
        final Map<String, Integer> order = new HashMap<String, Integer>();
        for (int i = 0; i < CHANNEL_GROUP_ORDER.length; i++) {
            order.put(CHANNEL_GROUP_ORDER[i], i);
        }
        Collections.sort(l, new Comparator<DepRef>() {
            @Override
            public int compare(DepRef a, DepRef b) {
                Integer x = order.get(a.group), y = order.get(b.group);
                int c = Integer.compare(x == null ? 50 : x, y == null ? 50 : y);
                if (c != 0) {
                    return c;
                }
                if (Q_CHANNEL.equals(qtype)) {
                    return 0; // keep the channel's own order (source, destinations, scripts)
                }
                c = String.CASE_INSENSITIVE_ORDER.compare(nz(a.ownerName), nz(b.ownerName));
                return c;
            }
        });
        return l;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private ResultTree.Grouping exploreGrouping(final String qtype) {
        return new ResultTree.Grouping() {
            @Override
            public String level1(DepRef r) {
                return r.group == null ? "Results" : r.group;
            }

            @Override
            public Icon icon1(String g, List<DepRef> rows) {
                return groupIcon(g);
            }

            @Override
            public String level2(DepRef r) {
                String g = r.group == null ? "" : r.group;
                if (g.equals("Problems")) {
                    return r.category;
                }
                if (g.equals("Deploy dependencies") || g.equals("Shared map variables")) {
                    return null;
                }
                if (g.equals("Configuration Map keys") || g.startsWith("Map variables")) {
                    return r.key;
                }
                if (g.equals("Code template functions")) {
                    return r.key + "()" + (r.message == null ? "" : "  —  " + r.message.replace("code template ", ""));
                }
                if (g.equals("Sends messages to")) {
                    return r.message;
                }
                return r.ownerName;
            }

            @Override
            public Icon icon2(DepRef r) {
                String g = r.group == null ? "" : r.group;
                if (g.equals("Problems")) {
                    return Ui.severityIcon(r.severity);
                }
                if (g.equals("Configuration Map keys")) {
                    return Ui.icon("wrench.png");
                }
                if (g.startsWith("Map variables")) {
                    return Ui.icon("table.png");
                }
                if (g.equals("Code template functions")) {
                    return Ui.icon("page_white_text.png");
                }
                if (g.equals("Sends messages to")) {
                    return r.targetId == null ? Ui.icon("cross.png") : Ui.typeIcon(T_CHANNEL);
                }
                return Ui.typeIcon(r.ownerType);
            }

            @Override
            public String leaf(DepRef r) {
                String g = r.group == null ? "" : r.group;
                if (g.equals("Deploy dependencies")) {
                    return r.relation + "  " + nz(r.message);
                }
                if (g.equals("Shared map variables")) {
                    return r.ownerName.equals(current == null ? null : current.title) ? r.relation : r.ownerName + " — " + r.relation;
                }
                if (r.location == null || r.location.equals("Channel")) {
                    return r.message != null ? r.message : r.ownerName;
                }
                return r.location;
            }

            @Override
            public Icon leafIcon(DepRef r) {
                if (r.part == null) {
                    return Ui.typeIcon(r.ownerType);
                }
                if (P_SCRIPTS.equals(r.part) || P_CODE.equals(r.part)) {
                    return Ui.icon("script_edit.png");
                }
                if (P_FILTER.equals(r.part)) {
                    return Ui.icon("table_gear.png");
                }
                if (P_TRANSFORMER.equals(r.part) || P_RESPONSE.equals(r.part)) {
                    return Ui.icon("table_edit.png");
                }
                return Ui.icon("wrench.png");
            }

            @Override
            public String how(DepRef r) {
                if ("Problems".equals(r.group)) {
                    return nz(r.key);
                }
                if ("Deploy dependencies".equals(r.group) || "Shared map variables".equals(r.group)) {
                    return "";
                }
                return nz(r.relation);
            }
        };
    }

    static Icon groupIcon(String g) {
        if (g == null) {
            return Ui.icon("folder_explore.png");
        }
        if (g.equals("Problems")) {
            return Ui.icon("exclamation.png");
        }
        if (g.equals("Receives messages from")) {
            return Ui.icon("arrow_undo.png");
        }
        if (g.equals("Sends messages to")) {
            return Ui.icon("arrow_redo.png");
        }
        if (g.startsWith("Configuration Map")) {
            return Ui.icon("wrench.png");
        }
        if (g.startsWith("Code template") || g.equals("Defined in") || g.equals("Code Templates")) {
            return Ui.icon("page_white_text.png");
        }
        if (g.startsWith("Map variables") || g.equals("Written by") || g.equals("Read by")) {
            return Ui.icon("table.png");
        }
        if (g.equals("Deploy dependencies")) {
            return Ui.icon("server.png");
        }
        if (g.equals("Shared map variables")) {
            return Ui.icon("world_link.png");
        }
        if (g.equals("Channels") || g.startsWith("Called from channels")) {
            return Ui.icon("application_form.png");
        }
        if (g.equals("Global Scripts") || g.startsWith("Called from global")) {
            return Ui.icon("script_edit.png");
        }
        return Ui.icon("folder_explore.png");
    }

    // =====================================================================
    // Reports
    // =====================================================================

    private void showReport() {
        if (report == null) {
            return;
        }
        List<DepRef> p = new ArrayList<DepRef>();
        int errors = 0, warnings = 0;
        for (DepRef r : report.problems) {
            boolean err = S_ERROR.equals(r.severity);
            if (err) {
                errors++;
            } else {
                warnings++;
            }
            if ((err && showErrors.isSelected()) || (!err && showWarnings.isSelected())) {
                p.add(r);
            }
        }
        Comparator<DepRef> c = new Comparator<DepRef>() {
            @Override
            public int compare(DepRef a, DepRef b) {
                int x = rank(a.severity) - rank(b.severity);
                if (x != 0) {
                    return x;
                }
                x = categoryRank(a.category) - categoryRank(b.category);
                if (x != 0) {
                    return x;
                }
                x = nz(a.category).compareTo(nz(b.category));
                if (x != 0) {
                    return x;
                }
                x = String.CASE_INSENSITIVE_ORDER.compare(nz(a.key), nz(b.key));
                return x != 0 ? x : String.CASE_INSENSITIVE_ORDER.compare(nz(a.ownerName), nz(b.ownerName));
            }
        };
        Collections.sort(p, c);
        List<DepRef> u = new ArrayList<DepRef>(report.unused);
        Collections.sort(u, c);
        problems.setRows(p, reportGrouping());
        unused.setRows(u, reportGrouping());
        if (!channelWindow) {
            tabs.setTitleAt(2, "Problems (" + errors + " error" + (errors == 1 ? "" : "s") + " · " + warnings + " warning" + (warnings == 1 ? "" : "s") + ")");
            tabs.setTitleAt(3, "Unused (" + report.unused.size() + ")");
            tabs.setIconAt(2, Ui.severityIcon(errors > 0 ? S_ERROR : warnings > 0 ? S_WARNING : S_INFO));
        }
        if (report.truncated) {
            status.setText(statusText() + " · long lists were shortened");
        }
    }

    private static final String[] CATEGORY_ORDER = { "Missing channel", "Missing Configuration Map key", "Code template library not enabled",
            "Function not available here", "Function not added to scripts", "Disabled target channel", "Unknown function", "Map variable never written",
            "Unresolved variable", "Function defined more than once", "Unused Configuration Map key", "Unused code template function",
            "Library not enabled for any channel", "Map variable never read", "Channel nobody sends to" };

    private static int categoryRank(String c) {
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            if (CATEGORY_ORDER[i].equals(c)) {
                return i;
            }
        }
        return CATEGORY_ORDER.length;
    }

    private static int rank(String severity) {
        return S_ERROR.equals(severity) ? 0 : S_WARNING.equals(severity) ? 1 : 2;
    }

    private static ResultTree.Grouping reportGrouping() {
        return new ResultTree.Grouping() {
            @Override
            public String level1(DepRef r) {
                return nz(r.category);
            }

            @Override
            public Icon icon1(String g, List<DepRef> rows) {
                return rows.isEmpty() ? null : Ui.severityIcon(rows.get(0).severity);
            }

            @Override
            public String level2(DepRef r) {
                return null;
            }

            @Override
            public Icon icon2(DepRef r) {
                return null;
            }

            @Override
            public String leaf(DepRef r) {
                if (T_CONFIG.equals(r.ownerType)) {
                    return r.ownerName;
                }
                String loc = r.location == null || r.location.equals("Channel") ? "" : r.location;
                if (loc.isEmpty() || loc.equals(r.ownerName)) {
                    return r.ownerName;
                }
                if (loc.startsWith(r.ownerName + " ›") || (r.ownerName.startsWith(loc) && !T_CHANNEL.equals(r.ownerType))) {
                    return loc;
                }
                return r.ownerName + "  ›  " + loc;
            }

            @Override
            public Icon leafIcon(DepRef r) {
                return Ui.typeIcon(r.ownerType);
            }

            @Override
            public String how(DepRef r) {
                return T_CONFIG.equals(r.ownerType) || "Channel nobody sends to".equals(r.category) ? "" : nz(r.key);
            }
        };
    }

    // =====================================================================
    // Row actions
    // =====================================================================

    @Override
    public void open(DepRef r) {
        Navigator.open(this, r);
    }

    @Override
    public boolean canOpen(DepRef r) {
        return Navigator.canOpen(r);
    }

    @Override
    public void exploreChannel(String channelId) {
        query(Q_CHANNEL, channelId, true);
    }

    @Override
    public boolean isCurrentChannel(String channelId) {
        return current != null && Q_CHANNEL.equals(current.type) && channelId != null && channelId.equals(current.key) && tabs.getSelectedIndex() == 0;
    }

    /** What to search for from a row: its name, by kind. */
    private String[] searchOf(DepRef r) {
        String g = nz(r.group) + " " + nz(r.category);
        String k = r.key;
        if (k == null || k.isEmpty()) {
            return null;
        }
        if (g.contains("Configuration Map")) {
            return new String[] { Q_CONFIG, k };
        }
        if (g.contains("function") || g.contains("Function") || g.contains("Code template library")) {
            return new String[] { Q_FUNCTION, k };
        }
        if (g.contains("Map variable") || g.contains("Unresolved variable") || g.contains("map variable")) {
            return new String[] { Q_MAPVAR, k };
        }
        if (g.contains("Missing channel")) {
            return new String[] { Q_TEXT, k };
        }
        if (current != null && !Q_CHANNEL.equals(current.type) && current.key.equals(k)) {
            return null; // that is the current search already
        }
        return null;
    }

    @Override
    public void search(DepRef r) {
        String[] s = searchOf(r);
        if (s != null) {
            query(s[0], s[1], true);
        }
    }

    @Override
    public String searchLabel(DepRef r) {
        String[] s = searchOf(r);
        if (s == null || (current != null && s[0].equals(current.type) && s[1].equals(current.key))) {
            return null;
        }
        return "Who uses " + (Q_FUNCTION.equals(s[0]) ? s[1] + "()" : "'" + s[1] + "'") + "?";
    }

    private void showInGraph(String channelId) {
        setFocus(channelId);
        tabs.setSelectedIndex(1);
        graph.reveal(channelId);
    }

    private void setFocus(String channelId) {
        updatingFocus = true;
        if (channelId == null) {
            focus.setSelectedIndex(0);
        } else {
            for (int i = 0; i < focus.getItemCount(); i++) {
                Object o = focus.getItemAt(i);
                if (o instanceof DepNode && ((DepNode) o).id.equals(channelId)) {
                    focus.setSelectedIndex(i);
                }
            }
        }
        updatingFocus = false;
        graph.focus = channelId;
        depth.setEnabled(channelId != null);
        graph.depth = (Integer) depth.getValue();
        graph.rebuild();
        graph.setSelected(channelId);
    }
}
