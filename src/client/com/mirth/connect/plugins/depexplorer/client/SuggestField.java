package com.mirth.connect.plugins.depexplorer.client;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Search box with suggestions while typing: channels, Configuration Map keys, code template functions and
 * map variables. Picking a suggestion runs the matching search; Enter on free text searches for the text.
 */
final class SuggestField extends JTextField {

    private static final long serialVersionUID = 1L;

    static final class Item {
        final String type; // Q_CHANNEL, Q_CONFIG, Q_FUNCTION, Q_MAPVAR, Q_TEXT
        final String key;
        final String label;
        final String detail;

        Item(String type, String key, String label, String detail) {
            this.type = type;
            this.key = key;
            this.label = label;
            this.detail = detail;
        }

        static String typeName(String type) {
            if (Q_CHANNEL.equals(type)) {
                return "Channel";
            } else if (Q_CONFIG.equals(type)) {
                return "Config Map";
            } else if (Q_FUNCTION.equals(type)) {
                return "Function";
            } else if (Q_MAPVAR.equals(type)) {
                return "Map variable";
            }
            return "Text";
        }

        static Color typeColor(String type) {
            if (Q_CHANNEL.equals(type)) {
                return Ui.ACCENT;
            } else if (Q_CONFIG.equals(type)) {
                return new Color(0x00, 0x7C, 0x6E);
            } else if (Q_FUNCTION.equals(type)) {
                return new Color(0x7B, 0x3F, 0xB5);
            } else if (Q_MAPVAR.equals(type)) {
                return new Color(0xB2, 0x6A, 0x00);
            }
            return Ui.MUTED;
        }
    }

    interface Chosen {
        void chosen(Item item);
    }

    private final List<Item> items = new ArrayList<Item>();
    private final DefaultListModel<Item> model = new DefaultListModel<Item>();
    private final JList<Item> list = new JList<Item>(model);
    private final JPopupMenu popup = new JPopupMenu();
    private Chosen chosen;
    private String typeFilter;
    private boolean adjusting;
    private String placeholder = "";

    SuggestField() {
        super(36);
        setFont(Ui.font().deriveFont(13f));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.BORDER), BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setFocusable(false);
        list.setVisibleRowCount(12);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        popup.setLayout(new BorderLayout());
        popup.add(sp, BorderLayout.CENTER);
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(Ui.BORDER));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    pick(model.get(i));
                }
            }
        });
        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int n = model.getSize();
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (!popup.isVisible()) {
                        update();
                    } else if (n > 0) {
                        list.setSelectedIndex(Math.min(n - 1, list.getSelectedIndex() + 1));
                        list.ensureIndexIsVisible(list.getSelectedIndex());
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_UP && popup.isVisible() && n > 0) {
                    list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1));
                    list.ensureIndexIsVisible(list.getSelectedIndex());
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    Item it = popup.isVisible() ? list.getSelectedValue() : null;
                    if (it == null) {
                        it = exact(getText().trim());
                    }
                    if (it == null && !getText().trim().isEmpty()) {
                        it = new Item(typeFilter == null ? Q_TEXT : typeFilter, getText().trim(), getText().trim(), null);
                    }
                    if (it != null) {
                        pick(it);
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    popup.setVisible(false);
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                popup.setVisible(false);
            }
        });
    }

    void setPlaceholder(String p) {
        placeholder = p;
        repaint();
    }

    void setChosen(Chosen c) {
        chosen = c;
    }

    void setItems(List<Item> l) {
        items.clear();
        items.addAll(l);
    }

    /** Limits suggestions to one type (null: all). */
    void setTypeFilter(String type) {
        typeFilter = type;
    }

    void setTextQuietly(String t) {
        adjusting = true;
        setText(t);
        adjusting = false;
        popup.setVisible(false);
    }

    private void changed() {
        if (!adjusting) {
            SwingUtilities.invokeLater(this::update);
        }
    }

    private Item exact(String t) {
        for (Item it : items) {
            if ((typeFilter == null || typeFilter.equals(it.type)) && it.key.equalsIgnoreCase(t)) {
                return it;
            }
        }
        return null;
    }

    private void pick(Item it) {
        popup.setVisible(false);
        setTextQuietly(it.label);
        if (chosen != null) {
            chosen.chosen(it);
        }
    }

    private void update() {
        String t = getText().trim().toLowerCase();
        model.clear();
        if (t.isEmpty() || !isShowing()) {
            popup.setVisible(false);
            return;
        }
        List<Item> prefix = new ArrayList<Item>();
        List<Item> contains = new ArrayList<Item>();
        for (Item it : items) {
            if (typeFilter != null && !typeFilter.equals(it.type)) {
                continue;
            }
            String k = it.label.toLowerCase();
            if (k.startsWith(t)) {
                prefix.add(it);
            } else if (k.contains(t) || (it.detail != null && it.detail.toLowerCase().contains(t))) {
                contains.add(it);
            }
            if (prefix.size() > 60) {
                break;
            }
        }
        List<Item> all = new ArrayList<Item>(prefix);
        all.addAll(contains);
        int max = Math.min(all.size(), 60);
        for (int i = 0; i < max; i++) {
            model.addElement(all.get(i));
        }
        if (typeFilter == null || Q_TEXT.equals(typeFilter)) {
            model.addElement(new Item(Q_TEXT, getText().trim(), getText().trim(), "find this text in every script and setting"));
        }
        if (model.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        list.setSelectedIndex(0);
        int rows = Math.min(model.getSize(), 12);
        popup.setPreferredSize(new Dimension(Math.max(getWidth(), 520), rows * 26 + 6));
        if (!popup.isVisible()) {
            popup.show(this, 0, getHeight());
        } else {
            popup.pack();
        }
        requestFocusInWindow();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getText().isEmpty() && !placeholder.isEmpty()) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(new Color(0x9C, 0xA3, 0xAF));
            g2.setFont(getFont());
            g2.drawString(placeholder, getInsets().left, (getHeight() + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2);
            g2.dispose();
        }
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<Item> {
        private static final long serialVersionUID = 1L;
        private final JLabel badge = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        Renderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 8));
            badge.setFont(Ui.bold(10f));
            badge.setOpaque(true);
            badge.setForeground(Color.WHITE);
            badge.setHorizontalAlignment(JLabel.CENTER);
            badge.setPreferredSize(new Dimension(84, 18));
            name.setFont(Ui.font().deriveFont(12.5f));
            detail.setForeground(Ui.MUTED);
            detail.setFont(Ui.font().deriveFont(11f));
            add(badge, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            add(detail, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> l, Item it, int index, boolean sel, boolean focus) {
            badge.setText(Item.typeName(it.type));
            badge.setBackground(Item.typeColor(it.type));
            name.setText(Q_TEXT.equals(it.type) ? "“" + it.label + "”" : it.label);
            String d = it.detail == null ? "" : it.detail;
            detail.setText(d.length() > 60 ? d.substring(0, 60) + "…" : d);
            setBackground(sel ? Ui.ACCENT_LIGHT : Color.WHITE);
            return this;
        }
    }
}
