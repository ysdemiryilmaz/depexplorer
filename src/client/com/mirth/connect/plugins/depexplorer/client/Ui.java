package com.mirth.connect.plugins.depexplorer.client;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/** Colors, fonts, icons and small helpers shared by the Dependency Explorer UI. */
final class Ui {

    private Ui() {}

    static final Color ACCENT = new Color(0x2F, 0x6F, 0xB3);
    static final Color ACCENT_LIGHT = new Color(0xE8, 0xF1, 0xFB);
    static final Color ERROR = new Color(0xC6, 0x28, 0x28);
    static final Color WARNING = new Color(0xE0, 0x8A, 0x00);
    static final Color INFO = new Color(0x1E, 0x76, 0xC8);
    static final Color OK = new Color(0x2E, 0x7D, 0x32);
    static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    static final Color BORDER = new Color(0xD5, 0xDA, 0xE1);
    static final Color CODE_BG = new Color(0xF6, 0xF8, 0xFA);
    static final Color PANEL_BG = new Color(0xFA, 0xFB, 0xFC);

    // flow graph edge colors
    static final Color E_WRITER_COLOR = new Color(0x2F, 0x6F, 0xB3);
    static final Color E_ROUTE_COLOR = new Color(0x7B, 0x3F, 0xB5);
    static final Color E_MENTION_COLOR = new Color(0x78, 0x86, 0x96);
    static final Color E_DEPLOY_COLOR = new Color(0x2E, 0x8B, 0x57);
    static final Color E_GLOBALMAP_COLOR = new Color(0xD9, 0x7A, 0x00);

    private static final Map<String, Icon> ICONS = new HashMap<String, Icon>();

    static Font font() {
        Font f = UIManager.getFont("Label.font");
        return f == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : f;
    }

    static Font bold(float size) {
        return font().deriveFont(Font.BOLD, size);
    }

    static Font mono() {
        return new Font(Font.MONOSPACED, Font.PLAIN, font().getSize());
    }

    /** One of Mirth's own icons (com/mirth/connect/client/ui/images). */
    static Icon icon(String name) {
        Icon i = ICONS.get(name);
        if (i == null) {
            try {
                i = new ImageIcon(com.mirth.connect.client.ui.Frame.class.getResource("images/" + name));
            } catch (Exception e) {
                i = dot(MUTED);
            }
            ICONS.put(name, i);
        }
        return i;
    }

    static Icon typeIcon(String ownerType) {
        if (T_CHANNEL.equals(ownerType)) {
            return icon("application_form.png");
        } else if (T_TEMPLATE.equals(ownerType)) {
            return icon("page_white_text.png");
        } else if (T_GLOBAL.equals(ownerType)) {
            return icon("script_edit.png");
        } else if (T_CONFIG.equals(ownerType)) {
            return icon("wrench.png");
        } else if (T_LIBRARY.equals(ownerType)) {
            return icon("page_white_stack.png");
        }
        return icon("bullet_black.png");
    }

    static Icon severityIcon(String severity) {
        if (S_ERROR.equals(severity)) {
            return icon("exclamation.png");
        } else if (S_WARNING.equals(severity)) {
            return icon("error.png");
        }
        return icon("information.png");
    }

    static Color severityColor(String severity) {
        return S_ERROR.equals(severity) ? ERROR : S_WARNING.equals(severity) ? WARNING : INFO;
    }

    /** A small filled circle. */
    static Icon dot(final Color c) {
        return new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.fillOval(x + 3, y + 3, 10, 10);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** A short line sample in the color/style of a graph edge (for legends). */
    static Icon lineIcon(final Color c, final boolean dashed) {
        return new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.setStroke(dashed ? new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 5f, 4f }, 0f) : new BasicStroke(2f));
                g2.drawLine(x, y + 8, x + 22, y + 8);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 26;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        if (f != null) {
            l.setFont(f);
        }
        if (c != null) {
            l.setForeground(c);
        }
        return l;
    }

    static JButton button(String text, String icon, String tip) {
        JButton b = new JButton(text);
        if (icon != null) {
            b.setIcon(icon(icon));
        }
        b.setToolTipText(tip);
        b.setFocusable(false);
        return b;
    }

    /** A button with a drawn chevron (Mirth has no plain arrow icons). */
    static JButton arrowButton(final boolean left, String tip) {
        JButton b = new JButton(new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.isEnabled() ? new Color(0x37, 0x41, 0x51) : new Color(0xB0, 0xB6, 0xBE));
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (left) {
                    g2.drawPolyline(new int[] { x + 10, x + 5, x + 10 }, new int[] { y + 3, y + 8, y + 13 }, 3);
                } else {
                    g2.drawPolyline(new int[] { x + 6, x + 11, x + 6 }, new int[] { y + 3, y + 8, y + 13 }, 3);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        });
        b.setToolTipText(tip);
        b.setFocusable(false);
        return b;
    }

    static JTextArea wrapText() {
        JTextArea t = new JTextArea();
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setOpaque(false);
        t.setBorder(BorderFactory.createEmptyBorder());
        t.setFont(font());
        return t;
    }

    static void pad(JComponent c, int t, int l, int b, int r) {
        c.setBorder(BorderFactory.createEmptyBorder(t, l, b, r));
    }

    static String html(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Short error dialog; the stack trace only via "Details". */
    static void showError(Component owner, Throwable t) {
        Throwable root = t;
        String msg = null;
        while (root != null) {
            if (root.getMessage() != null && !root.getMessage().trim().isEmpty()) {
                msg = root.getMessage();
            }
            if (root.getCause() == null || root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        if (msg == null || msg.contains("Method failed") || msg.contains("Request failed")) {
            msg = "The server request failed. See the Mirth server log (logs/mirth.log) for details.";
        } else if (msg.length() > 600) {
            msg = msg.substring(0, 600) + "…";
        }
        JTextArea ta = new JTextArea(msg, Math.min(8, Math.max(1, msg.split("\n").length)), 60);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setOpaque(false);
        JOptionPane.showMessageDialog(owner, ta, PLUGIN_NAME, JOptionPane.ERROR_MESSAGE);
    }

    static String plural(int n, String one) {
        return n + " " + one + (n == 1 ? "" : "s");
    }
}
