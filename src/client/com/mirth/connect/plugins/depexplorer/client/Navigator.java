package com.mirth.connect.plugins.depexplorer.client;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;

import org.jdesktop.swingx.JXTreeTable;
import org.jdesktop.swingx.treetable.TreeTableModel;

import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.InvalidChannel;
import com.mirth.connect.plugins.depexplorer.DepRef;

/**
 * "Open in Mirth": opens the channel editor at the connector, filter rule or transformer step of a result
 * (or the code template, global script, Configuration Map key) and highlights the code line.
 *
 * Mirth has no public API for selecting a destination or a step, so the editor's own components are driven
 * the way a user would click them. Every step is defensive: if something is not found in a Mirth version,
 * the editor simply stays at the nearest place that could be opened.
 */
final class Navigator {

    private Navigator() {}

    static boolean canOpen(DepRef r) {
        return r != null && r.ownerId != null && (T_CHANNEL.equals(r.ownerType) || T_TEMPLATE.equals(r.ownerType) || T_GLOBAL.equals(r.ownerType)
                || T_CONFIG.equals(r.ownerType) || T_LIBRARY.equals(r.ownerType));
    }

    static void open(final Component owner, final DepRef r) {
        final Frame f = PlatformUI.MIRTH_FRAME;
        if (f.isSaveEnabled() && !f.confirmLeave()) {
            return;
        }
        if (T_CHANNEL.equals(r.ownerType)) {
            openChannel(owner, r.ownerId, r);
        } else if (T_TEMPLATE.equals(r.ownerType) || T_LIBRARY.equals(r.ownerType)) {
            f.doEditCodeTemplates();
            poll(30, () -> selectTreeTableRow(field(f.codeTemplatePanel, "templateTreeTable"), r.ownerId), () -> highlight(r.snippet, r.line));
        } else if (T_GLOBAL.equals(r.ownerType)) {
            f.doEditGlobalScripts();
            poll(30, () -> {
                Object sp = field(f.globalScriptsPanel, "scriptPanel");
                Object combo = field(sp, "scriptComboBox");
                if (!(combo instanceof JComboBox) || !((JComboBox<?>) combo).isShowing()) {
                    return false;
                }
                selectScript((JComboBox<?>) combo, r.field);
                return true;
            }, () -> highlight(r.snippet, r.line));
        } else if (T_CONFIG.equals(r.ownerType)) {
            f.doShowSettings();
            poll(30, () -> {
                Object tabs = field(f.settingsPane, "tabbedPane");
                if (!(tabs instanceof JTabbedPane)) {
                    return false;
                }
                JTabbedPane tp = (JTabbedPane) tabs;
                int i = tp.indexOfTab("Configuration Map");
                if (i < 0) {
                    return false;
                }
                if (tp.getSelectedIndex() != i) {
                    tp.setSelectedIndex(i);
                }
                Object table = field(f.settingsPane.getSettingsPanel("Configuration Map"), "configurationMapTable");
                return table instanceof JTable && selectTableRow((JTable) table, r.ownerId);
            }, null);
        }
    }

    static void openChannel(final Component owner, final String channelId, final DepRef r) {
        final Frame f = PlatformUI.MIRTH_FRAME;
        Api.run(owner, null, "Loading the channel", () -> f.mirthClient.getChannel(channelId, false), channel -> {
            if (channel == null) {
                JOptionPane.showMessageDialog(owner, "The channel no longer exists.", PLUGIN_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (channel instanceof InvalidChannel) {
                JOptionPane.showMessageDialog(owner, "The channel \"" + channel.getName() + "\" cannot be opened in the editor: it uses an extension that is not loaded.",
                        PLUGIN_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            Window.toBackIfDialog(owner);
            f.editChannel(channel);
            if (r != null) {
                SwingUtilities.invokeLater(() -> position(f, channel, r));
            }
        });
    }

    /** Selects the tab / destination / step in the open channel editor. */
    private static void position(Frame f, Channel channel, DepRef r) {
        try {
            Object view = field(f.channelEditPanel, "channelView");
            if (!(view instanceof JTabbedPane)) {
                return;
            }
            JTabbedPane tabs = (JTabbedPane) view;
            String part = r.part == null ? P_SUMMARY : r.part;
            if (P_SUMMARY.equals(part)) {
                tabs.setSelectedIndex(0);
                return;
            }
            if (P_SCRIPTS.equals(part)) {
                int i = tabIndex(tabs, "Scripts");
                if (i >= 0) {
                    tabs.setSelectedIndex(i);
                }
                Object sp = field(f.channelEditPanel, "scriptsPanel");
                Object combo = field(sp, "scriptComboBox");
                if (combo instanceof JComboBox) {
                    selectScript((JComboBox<?>) combo, r.field);
                }
                highlight(r.snippet, r.line);
                return;
            }
            if (r.connector <= 0) {
                int i = tabIndex(tabs, "Source");
                tabs.setSelectedIndex(i >= 0 ? i : 1);
            } else {
                int i = tabIndex(tabs, "Destinations");
                tabs.setSelectedIndex(i >= 0 ? i : 2);
                Object table = field(f.channelEditPanel, "destinationTable");
                List<Connector> dests = channel.getDestinationConnectors();
                for (int m = 0; m < dests.size(); m++) {
                    if (dests.get(m).getMetaDataId() == r.connector && table instanceof JTable) {
                        JTable t = (JTable) table;
                        int row = t.convertRowIndexToView(m);
                        if (row >= 0) {
                            t.setRowSelectionInterval(row, row);
                            t.scrollRectToVisible(t.getCellRect(row, 0, true));
                        }
                        break;
                    }
                }
            }
            if (P_FILTER.equals(part) || P_TRANSFORMER.equals(part) || P_RESPONSE.equals(part)) {
                final Object pane;
                if (P_FILTER.equals(part)) {
                    f.doEditFilter();
                    pane = f.channelEditPanel.filterPane;
                } else if (P_TRANSFORMER.equals(part)) {
                    f.doEditTransformer();
                    pane = f.channelEditPanel.transformerPane;
                } else {
                    f.doEditResponseTransformer();
                    pane = f.channelEditPanel.transformerPane;
                }
                if (r.step != null) {
                    SwingUtilities.invokeLater(() -> {
                        selectStep(field(pane, "treeTable"), r.step);
                        highlight(r.snippet, r.line);
                    });
                }
                return;
            }
            highlight(r.snippet, r.line);
        } catch (Throwable t) {
            // stay where the editor is
        }
    }

    private static int tabIndex(JTabbedPane tabs, String prefix) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            String t = tabs.getTitleAt(i);
            if (t != null && t.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void selectScript(JComboBox<?> combo, String field) {
        if (field == null) {
            return;
        }
        String want = field.replace(" Script", "").trim();
        for (int i = 0; i < combo.getItemCount(); i++) {
            Object it = combo.getItemAt(i);
            if (it != null && it.toString().equalsIgnoreCase(want)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    /** Selects the filter rule / transformer step with the given sequence number ("2", "1-0" ...). */
    private static void selectStep(Object treeTable, String seq) {
        if (!(treeTable instanceof JXTreeTable)) {
            return;
        }
        JXTreeTable tt = (JXTreeTable) treeTable;
        tt.expandAll();
        for (int row = 0; row < tt.getRowCount(); row++) {
            TreePath p = tt.getPathForRow(row);
            if (p == null) {
                continue;
            }
            Object node = p.getLastPathComponent();
            Object element = call(node, "getElement");
            Object s = call(element, "getSequenceNumber");
            if (seq.equals(String.valueOf(s))) {
                tt.setRowSelectionInterval(row, row);
                tt.scrollRectToVisible(tt.getCellRect(row, 0, true));
                return;
            }
        }
    }

    /** Selects the row of a tree table (code templates) whose node has the id in any column. */
    private static boolean selectTreeTableRow(Object treeTable, String id) {
        if (!(treeTable instanceof JXTreeTable) || !((JXTreeTable) treeTable).isShowing()) {
            return false;
        }
        JXTreeTable tt = (JXTreeTable) treeTable;
        TreeTableModel m = tt.getTreeTableModel();
        if (tt.getRowCount() == 0) {
            return false;
        }
        tt.expandAll();
        for (int row = 0; row < tt.getRowCount(); row++) {
            TreePath p = tt.getPathForRow(row);
            if (p == null) {
                continue;
            }
            Object node = p.getLastPathComponent();
            for (int c = 0; c < m.getColumnCount(); c++) {
                Object v;
                try {
                    v = m.getValueAt(node, c);
                } catch (Exception e) {
                    continue;
                }
                if (id.equals(v)) {
                    tt.setRowSelectionInterval(row, row);
                    tt.scrollRectToVisible(tt.getCellRect(row, 0, true));
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean selectTableRow(JTable t, String key) {
        if (!t.isShowing() || t.getRowCount() == 0) {
            return false;
        }
        for (int row = 0; row < t.getRowCount(); row++) {
            if (key.equals(String.valueOf(t.getValueAt(row, 0)))) {
                t.setRowSelectionInterval(row, row);
                t.scrollRectToVisible(t.getCellRect(row, 0, true));
                return true;
            }
        }
        return true; // the key is not in the table (anymore); stop trying
    }

    // =====================================================================
    // Highlighting the code line in whatever editor is showing
    // =====================================================================

    static void highlight(final String snippet, final int line) {
        if (snippet == null || snippet.trim().length() < 2) {
            return;
        }
        String n = snippet.trim();
        if (n.endsWith("…")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.length() > 80) {
            n = n.substring(0, 80);
        }
        final String needle = n;
        poll(16, () -> {
            JTextComponent c = findText(PlatformUI.MIRTH_FRAME.getContentPane(), needle);
            if (c == null) {
                return false;
            }
            select(c, needle, line);
            return true;
        }, null);
    }

    private static JTextComponent findText(Container root, String needle) {
        for (Component c : root.getComponents()) {
            if (!c.isShowing()) {
                continue;
            }
            if (c instanceof JTextComponent) {
                String t = ((JTextComponent) c).getText();
                if (t != null && t.contains(needle)) {
                    return (JTextComponent) c;
                }
            }
            if (c instanceof Container) {
                JTextComponent r = findText((Container) c, needle);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void select(JTextComponent c, String needle, int line) {
        String text = c.getText().replace("\r\n", "\n");
        int start = -1;
        if (line > 0) {
            int s = 0;
            for (int l = 1; l < line && s >= 0; l++) {
                s = text.indexOf('\n', s);
                s = s < 0 ? -1 : s + 1;
            }
            if (s >= 0) {
                int e = text.indexOf('\n', s);
                String ln = text.substring(s, e < 0 ? text.length() : e);
                int k = ln.indexOf(needle);
                if (k >= 0) {
                    start = s + k;
                }
            }
        }
        if (start < 0) {
            start = text.indexOf(needle);
        }
        if (start < 0) {
            return;
        }
        int end = start + needle.length();
        try {
            c.requestFocusInWindow();
            c.setCaretPosition(start);
            c.moveCaretPosition(end);
            @SuppressWarnings("deprecation")
            Rectangle r = c.modelToView(start);
            if (r != null) {
                r.height += 60;
                c.scrollRectToVisible(r);
            }
        } catch (Exception ignore) {
            // not important
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    interface Step {
        boolean tryIt();
    }

    /** Retries a step every 200 ms (the editors load in the background) up to n times, then runs "then". */
    private static void poll(final int times, final Step step, final Runnable then) {
        final int[] left = { times };
        final Timer t = new Timer(200, null);
        t.addActionListener(e -> {
            boolean ok;
            try {
                ok = step.tryIt();
            } catch (Throwable x) {
                ok = false;
            }
            if (ok || --left[0] <= 0) {
                t.stop();
                if (ok && then != null) {
                    then.run();
                }
            }
        });
        t.setInitialDelay(150);
        t.start();
    }

    static Object field(Object o, String name) {
        if (o == null) {
            return null;
        }
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // superclass
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static Object call(Object o, String method) {
        if (o == null) {
            return null;
        }
        try {
            Method m = o.getClass().getMethod(method);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Moves a Dependency Explorer dialog behind the main window so the editor is visible. */
    static final class Window {
        static void toBackIfDialog(Component c) {
            java.awt.Window w = c == null ? null : SwingUtilities.getWindowAncestor(c);
            if (w instanceof javax.swing.JDialog && w != PlatformUI.MIRTH_FRAME) {
                w.toBack();
                PlatformUI.MIRTH_FRAME.toFront();
            }
        }
    }
}
