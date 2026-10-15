package com.mirth.connect.plugins.depexplorer.client;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;

import com.mirth.connect.plugins.depexplorer.DepEdge;
import com.mirth.connect.plugins.depexplorer.DepNode;
import com.mirth.connect.plugins.depexplorer.DepOverview;

/**
 * The channel flow graph: channels as boxes, message flows and other dependencies as arrows, laid out in
 * layers from left (senders) to right (receivers). Zoom with the mouse wheel, pan by dragging the background,
 * move a box by dragging it.
 */
final class FlowGraph extends JComponent {

    private static final long serialVersionUID = 1L;
    static final int W = 200, H = 58, HGAP = 110, VGAP = 26, MARGIN = 40;

    interface Listener {
        void selected(String channelId);

        void explore(String channelId);

        void popup(String channelId, MouseEvent e);
    }

    static final class GNode {
        String id;
        DepNode node;
        String missingRef;
        double x, y;
        int layer;
        double order;

        boolean missing() {
            return node == null;
        }

        String name() {
            return node != null ? node.name : "Missing channel";
        }
    }

    static final class GEdge {
        DepEdge e;
        GNode from, to;
        Path2D.Double path;
        Point2D.Double labelAt;
        int parallel;
        int parallelCount;
    }

    // ---- options
    boolean showDeploy = true;
    boolean showGlobalMap = true;
    boolean showMentions = true;
    boolean showUnconnected = false;
    String focus;
    int depth = 2;

    private DepOverview overview;
    private final Map<String, GNode> nodes = new LinkedHashMap<String, GNode>();
    private final List<GEdge> edges = new ArrayList<GEdge>();
    private Listener listener;
    private String selected;
    private String hover;
    private GEdge hoverEdge;
    private double scale = 1.0, tx = 20, ty = 20;
    private Point dragStart;
    private GNode dragNode;
    private double dragNodeX, dragNodeY;
    private boolean fitPending = true;
    private String emptyText = "No channels to show.";

    FlowGraph() {
        setOpaque(true);
        setBackground(java.awt.Color.WHITE);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.isPopupTrigger()) {
                    popup(e);
                    return;
                }
                GNode n = nodeAt(e.getPoint());
                dragStart = e.getPoint();
                dragNode = n;
                if (n != null) {
                    dragNodeX = n.x;
                    dragNodeY = n.y;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    popup(e);
                }
                dragStart = null;
                dragNode = null;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                GNode n = nodeAt(e.getPoint());
                String id = n == null || n.missing() ? null : n.id;
                if (e.getClickCount() == 2 && id != null) {
                    if (listener != null) {
                        listener.explore(id);
                    }
                    return;
                }
                selected = id;
                repaint();
                if (listener != null) {
                    listener.selected(id);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) {
                    return;
                }
                double dx = e.getX() - dragStart.x, dy = e.getY() - dragStart.y;
                if (dragNode != null) {
                    dragNode.x = dragNodeX + dx / scale;
                    dragNode.y = dragNodeY + dy / scale;
                    routeEdges();
                } else {
                    tx += dx;
                    ty += dy;
                    dragStart = e.getPoint();
                }
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                GNode n = nodeAt(e.getPoint());
                String h = n == null ? null : n.id;
                GEdge he = n == null ? edgeAt(e.getPoint()) : null;
                if (!eq(h, hover) || he != hoverEdge) {
                    hover = h;
                    hoverEdge = he;
                    setCursor(java.awt.Cursor.getPredefinedCursor(n != null ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
                hoverEdge = null;
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(e.getPoint(), e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (fitPending && getWidth() > 0) {
                    fit();
                }
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setOverview(DepOverview o) {
        overview = o;
        rebuild();
    }

    void setSelected(String id) {
        selected = id;
        repaint();
    }

    String getSelected() {
        return selected;
    }

    int visibleNodes() {
        return nodes.size();
    }

    // =====================================================================
    // Building the graph
    // =====================================================================

    private boolean visible(DepEdge e) {
        if (E_DEPLOY.equals(e.kind)) {
            return showDeploy;
        }
        if (E_GLOBALMAP.equals(e.kind)) {
            return showGlobalMap;
        }
        if (E_MENTION.equals(e.kind)) {
            return showMentions;
        }
        return true;
    }

    private static boolean flow(DepEdge e) {
        return E_WRITER.equals(e.kind) || E_ROUTE.equals(e.kind) || E_MENTION.equals(e.kind);
    }

    private static String target(DepEdge e) {
        return e.toId != null ? e.toId : "?" + e.targetRef;
    }

    void rebuild() {
        nodes.clear();
        edges.clear();
        if (overview == null) {
            repaint();
            return;
        }
        Map<String, DepNode> byId = new HashMap<String, DepNode>();
        for (DepNode n : overview.channels) {
            byId.put(n.id, n);
        }
        List<DepEdge> vis = new ArrayList<DepEdge>();
        for (DepEdge e : overview.edges) {
            if (visible(e) && byId.containsKey(e.fromId)) {
                vis.add(e);
            }
        }
        // which channels to show
        Set<String> show = new HashSet<String>();
        if (focus != null && byId.containsKey(focus)) {
            Map<String, List<String>> adj = new HashMap<String, List<String>>();
            for (DepEdge e : vis) {
                adj(adj, e.fromId).add(target(e));
                adj(adj, target(e)).add(e.fromId);
            }
            ArrayDeque<String> q = new ArrayDeque<String>();
            Map<String, Integer> dist = new HashMap<String, Integer>();
            q.add(focus);
            dist.put(focus, 0);
            while (!q.isEmpty()) {
                String c = q.poll();
                int d = dist.get(c);
                if (d >= depth) {
                    continue;
                }
                for (String n : adj(adj, c)) {
                    if (!dist.containsKey(n)) {
                        dist.put(n, d + 1);
                        q.add(n);
                    }
                }
            }
            show.addAll(dist.keySet());
        } else {
            for (DepEdge e : vis) {
                show.add(e.fromId);
                show.add(target(e));
            }
            if (showUnconnected) {
                show.addAll(byId.keySet());
            }
        }
        for (DepNode n : overview.channels) {
            if (show.contains(n.id)) {
                GNode g = new GNode();
                g.id = n.id;
                g.node = n;
                nodes.put(n.id, g);
            }
        }
        for (DepEdge e : vis) {
            String t = target(e);
            if (!show.contains(e.fromId) || !show.contains(t)) {
                continue;
            }
            GNode to = nodes.get(t);
            if (to == null) {
                to = new GNode();
                to.id = t;
                to.missingRef = e.targetRef;
                nodes.put(t, to);
            }
            GEdge g = new GEdge();
            g.e = e;
            g.from = nodes.get(e.fromId);
            g.to = to;
            if (g.from != null) {
                edges.add(g);
            }
        }
        emptyText = focus != null ? "This channel has no connections of the selected kinds." : overview.channels.isEmpty() ? "There are no channels."
                : "No channel sends messages to another channel (Channel Writer / router), and no other links were found. Tick \"Unconnected channels\" to show every channel.";
        layoutGraph();
        fitPending = true;
        if (getWidth() > 0) {
            fit();
        }
        repaint();
    }

    private static List<String> adj(Map<String, List<String>> m, String k) {
        List<String> l = m.get(k);
        if (l == null) {
            l = new ArrayList<String>();
            m.put(k, l);
        }
        return l;
    }

    // =====================================================================
    // Layout: components stacked vertically, each laid out in layers
    // =====================================================================

    private void layoutGraph() {
        // connected components
        Map<String, String> parent = new HashMap<String, String>();
        for (String id : nodes.keySet()) {
            parent.put(id, id);
        }
        for (GEdge e : edges) {
            union(parent, e.from.id, e.to.id);
        }
        Map<String, List<GNode>> comps = new LinkedHashMap<String, List<GNode>>();
        for (GNode n : nodes.values()) {
            String r = find(parent, n.id);
            List<GNode> l = comps.get(r);
            if (l == null) {
                l = new ArrayList<GNode>();
                comps.put(r, l);
            }
            l.add(n);
        }
        List<List<GNode>> list = new ArrayList<List<GNode>>(comps.values());
        // the focused channel's component first, then bigger components first; single channels last
        Collections.sort(list, new Comparator<List<GNode>>() {
            @Override
            public int compare(List<GNode> a, List<GNode> b) {
                boolean fa = focus != null && contains(a, focus), fb = focus != null && contains(b, focus);
                if (fa != fb) {
                    return fa ? -1 : 1;
                }
                if (a.size() != b.size()) {
                    return b.size() - a.size();
                }
                return a.get(0).name().compareToIgnoreCase(b.get(0).name());
            }
        });
        double y = 0;
        List<GNode> singles = new ArrayList<GNode>();
        for (List<GNode> c : list) {
            if (c.size() == 1) {
                singles.add(c.get(0));
                continue;
            }
            double h = layoutComponent(c, y);
            y += h + VGAP * 3;
        }
        // single channels in a grid
        int cols = Math.max(1, Math.min(6, (int) Math.ceil(Math.sqrt(singles.size() * 2))));
        Collections.sort(singles, new Comparator<GNode>() {
            @Override
            public int compare(GNode a, GNode b) {
                return a.name().compareToIgnoreCase(b.name());
            }
        });
        for (int i = 0; i < singles.size(); i++) {
            GNode n = singles.get(i);
            n.x = (i % cols) * (W + 40);
            n.y = y + (i / cols) * (H + VGAP);
        }
        routeEdges();
    }

    private static boolean contains(List<GNode> l, String id) {
        for (GNode n : l) {
            if (n.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Lays out one connected component at vertical offset y0; returns its height. */
    private double layoutComponent(List<GNode> comp, double y0) {
        Set<GNode> in = new HashSet<GNode>(comp);
        Map<GNode, List<GNode>> out = new HashMap<GNode, List<GNode>>();
        for (GEdge e : edges) {
            if (in.contains(e.from) && flow(e.e) && e.from != e.to) {
                List<GNode> l = out.get(e.from);
                if (l == null) {
                    l = new ArrayList<GNode>();
                    out.put(e.from, l);
                }
                l.add(e.to);
            }
        }
        // remove cycles: DFS, edges to nodes on the stack are back edges
        Set<String> back = new HashSet<String>();
        Map<GNode, Integer> state = new HashMap<GNode, Integer>();
        List<GNode> sorted = new ArrayList<GNode>(comp);
        Collections.sort(sorted, new Comparator<GNode>() {
            @Override
            public int compare(GNode a, GNode b) {
                return a.name().compareToIgnoreCase(b.name());
            }
        });
        for (GNode n : sorted) {
            dfs(n, out, state, back);
        }
        // longest path layering
        Map<GNode, Integer> indeg = new HashMap<GNode, Integer>();
        for (GNode n : comp) {
            indeg.put(n, 0);
            n.layer = 0;
        }
        for (Map.Entry<GNode, List<GNode>> e : out.entrySet()) {
            for (GNode t : e.getValue()) {
                if (!back.contains(e.getKey().id + ">" + t.id)) {
                    indeg.put(t, indeg.get(t) + 1);
                }
            }
        }
        ArrayDeque<GNode> q = new ArrayDeque<GNode>();
        for (GNode n : sorted) {
            if (indeg.get(n) == 0) {
                q.add(n);
            }
        }
        while (!q.isEmpty()) {
            GNode n = q.poll();
            List<GNode> l = out.get(n);
            if (l == null) {
                continue;
            }
            for (GNode t : l) {
                if (back.contains(n.id + ">" + t.id)) {
                    continue;
                }
                t.layer = Math.max(t.layer, n.layer + 1);
                indeg.put(t, indeg.get(t) - 1);
                if (indeg.get(t) == 0) {
                    q.add(t);
                }
            }
        }
        // channels nobody (visible) sends to: as close as possible to the channels they send to, so their
        // arrows stay short instead of crossing the whole graph from the first column
        Set<GNode> hasPred = new HashSet<GNode>();
        for (Map.Entry<GNode, List<GNode>> e : out.entrySet()) {
            for (GNode t : e.getValue()) {
                if (!back.contains(e.getKey().id + ">" + t.id)) {
                    hasPred.add(t);
                }
            }
        }
        for (GNode n : comp) {
            List<GNode> succ = out.get(n);
            if (hasPred.contains(n) || succ == null || succ.isEmpty()) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            for (GNode t : succ) {
                if (!back.contains(n.id + ">" + t.id)) {
                    min = Math.min(min, t.layer);
                }
            }
            if (min != Integer.MAX_VALUE && min - 1 > n.layer) {
                n.layer = min - 1;
            }
        }
        // nodes linked only by non-flow edges (deploy / shared map): next to their partner
        for (GEdge e : edges) {
            if (in.contains(e.from) && !flow(e.e) && e.to.layer == 0 && e.from.layer > 0 && (out.get(e.to) == null)) {
                e.to.layer = e.from.layer;
            }
        }
        int layers = 0;
        for (GNode n : comp) {
            layers = Math.max(layers, n.layer + 1);
        }
        List<List<GNode>> byLayer = new ArrayList<List<GNode>>();
        for (int i = 0; i < layers; i++) {
            byLayer.add(new ArrayList<GNode>());
        }
        for (GNode n : sorted) {
            byLayer.get(n.layer).add(n);
        }
        for (List<GNode> l : byLayer) {
            for (int i = 0; i < l.size(); i++) {
                l.get(i).order = i;
            }
        }
        // barycenter ordering, a few sweeps in both directions
        Map<GNode, List<GNode>> nb = new HashMap<GNode, List<GNode>>();
        for (GEdge e : edges) {
            if (in.contains(e.from) && e.from != e.to) {
                list(nb, e.from).add(e.to);
                list(nb, e.to).add(e.from);
            }
        }
        for (int it = 0; it < 6; it++) {
            boolean down = it % 2 == 0;
            for (int li = down ? 1 : layers - 2; down ? li < layers : li >= 0; li += down ? 1 : -1) {
                final int ref = down ? li - 1 : li + 1;
                List<GNode> l = byLayer.get(li);
                final Map<GNode, Double> bc = new HashMap<GNode, Double>();
                for (GNode n : l) {
                    double sum = 0;
                    int c = 0;
                    for (GNode m : list(nb, n)) {
                        if (m.layer == ref) {
                            sum += m.order;
                            c++;
                        }
                    }
                    bc.put(n, c == 0 ? n.order : sum / c);
                }
                Collections.sort(l, new Comparator<GNode>() {
                    @Override
                    public int compare(GNode a, GNode b) {
                        return Double.compare(bc.get(a), bc.get(b));
                    }
                });
                for (int i = 0; i < l.size(); i++) {
                    l.get(i).order = i;
                }
            }
        }
        int maxRows = 0;
        for (List<GNode> l : byLayer) {
            maxRows = Math.max(maxRows, l.size());
        }
        double height = maxRows * (H + VGAP) - VGAP;
        for (int li = 0; li < layers; li++) {
            List<GNode> l = byLayer.get(li);
            double off = (height - (l.size() * (H + VGAP) - VGAP)) / 2;
            for (int i = 0; i < l.size(); i++) {
                GNode n = l.get(i);
                n.x = li * (W + HGAP);
                n.y = y0 + off + i * (H + VGAP);
            }
        }
        return height;
    }

    private static List<GNode> list(Map<GNode, List<GNode>> m, GNode k) {
        List<GNode> l = m.get(k);
        if (l == null) {
            l = new ArrayList<GNode>();
            m.put(k, l);
        }
        return l;
    }

    private static void dfs(GNode n, Map<GNode, List<GNode>> out, Map<GNode, Integer> state, Set<String> back) {
        Integer s = state.get(n);
        if (s != null) {
            return;
        }
        // iterative DFS (deep chains must not overflow the stack)
        ArrayDeque<Object[]> stack = new ArrayDeque<Object[]>();
        state.put(n, 1);
        stack.push(new Object[] { n, 0 });
        while (!stack.isEmpty()) {
            Object[] top = stack.peek();
            GNode c = (GNode) top[0];
            int i = (Integer) top[1];
            List<GNode> l = out.get(c);
            if (l == null || i >= l.size()) {
                state.put(c, 2);
                stack.pop();
                continue;
            }
            top[1] = i + 1;
            GNode t = l.get(i);
            Integer ts = state.get(t);
            if (ts == null) {
                state.put(t, 1);
                stack.push(new Object[] { t, 0 });
            } else if (ts == 1) {
                back.add(c.id + ">" + t.id);
            }
        }
    }

    private static String find(Map<String, String> p, String x) {
        while (!p.get(x).equals(x)) {
            p.put(x, p.get(p.get(x)));
            x = p.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> p, String a, String b) {
        String ra = find(p, a), rb = find(p, b);
        if (!ra.equals(rb)) {
            p.put(ra, rb);
        }
    }

    /** Computes the curve of every edge (after layout or after a box was moved). */
    private void routeEdges() {
        Map<String, List<GEdge>> pairs = new HashMap<String, List<GEdge>>();
        for (GEdge e : edges) {
            String k = e.from.id.compareTo(e.to.id) < 0 ? e.from.id + "|" + e.to.id : e.to.id + "|" + e.from.id;
            List<GEdge> l = pairs.get(k);
            if (l == null) {
                l = new ArrayList<GEdge>();
                pairs.put(k, l);
            }
            l.add(e);
        }
        for (List<GEdge> l : pairs.values()) {
            for (int i = 0; i < l.size(); i++) {
                l.get(i).parallel = i;
                l.get(i).parallelCount = l.size();
            }
        }
        for (GEdge e : edges) {
            double off = (e.parallel - (e.parallelCount - 1) / 2.0) * 12;
            Path2D.Double p = new Path2D.Double();
            if (e.from == e.to) {
                double x = e.from.x + W - 30, y = e.from.y;
                p.moveTo(x, y);
                p.curveTo(x + 10, y - 45, x + 60, y - 10, e.from.x + W, y + H / 3.0);
                e.labelAt = new Point2D.Double(x + 30, y - 25);
            } else if (e.to.x > e.from.x + W / 2.0) {
                double x1 = e.from.x + W, y1 = e.from.y + H / 2.0 + off;
                double x2 = e.to.x, y2 = e.to.y + H / 2.0 + off;
                double dx = Math.max(40, (x2 - x1) / 2);
                p.moveTo(x1, y1);
                p.curveTo(x1 + dx, y1, x2 - dx, y2, x2, y2);
                e.labelAt = new Point2D.Double((x1 + x2) / 2, (y1 + y2) / 2);
            } else if (e.to.x < e.from.x - W / 2.0) {
                // backwards: from the left side of the sender to the right side of the target
                double x1 = e.from.x, y1 = e.from.y + H / 2.0 + off;
                double x2 = e.to.x + W, y2 = e.to.y + H / 2.0 + off;
                double dx = Math.max(45, (x1 - x2) / 2);
                p.moveTo(x1, y1);
                p.curveTo(x1 - dx, y1, x2 + dx, y2, x2, y2);
                e.labelAt = new Point2D.Double((x1 + x2) / 2, (y1 + y2) / 2);
            } else {
                // same column: an arc on the right side
                boolean downwards = e.to.y > e.from.y;
                double x1 = e.from.x + W, y1 = e.from.y + H / 2.0 + off;
                double x2 = e.to.x + W, y2 = e.to.y + H / 2.0 + off;
                double bulge = 50 + Math.min(120, Math.abs(y2 - y1) * 0.25);
                p.moveTo(x1, y1);
                p.curveTo(x1 + bulge, y1 + (downwards ? 10 : -10), x2 + bulge, y2, x2, y2);
                e.labelAt = new Point2D.Double(x1 + bulge * 0.75, (y1 + y2) / 2);
            }
            e.path = p;
        }
    }

    // =====================================================================
    // Zoom / fit / hit testing
    // =====================================================================

    Rectangle graphBounds() {
        if (nodes.isEmpty()) {
            return new Rectangle(0, 0, 1, 1);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (GNode n : nodes.values()) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + W);
            maxY = Math.max(maxY, n.y + H);
        }
        for (GEdge e : edges) {
            Rectangle r = e.path.getBounds();
            minX = Math.min(minX, r.x);
            minY = Math.min(minY, r.y);
            maxX = Math.max(maxX, r.x + r.width);
            maxY = Math.max(maxY, r.y + r.height);
        }
        return new Rectangle((int) minX, (int) minY, (int) (maxX - minX), (int) (maxY - minY));
    }

    void fit() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        fitPending = false;
        Rectangle b = graphBounds();
        double s = Math.min((getWidth() - 2.0 * MARGIN) / Math.max(1, b.width), (getHeight() - 2.0 * MARGIN - 30) / Math.max(1, b.height));
        scale = Math.max(0.15, Math.min(1.2, s));
        tx = (getWidth() - b.width * scale) / 2 - b.x * scale;
        ty = Math.max(MARGIN, (getHeight() - 30 - b.height * scale) / 2) - b.y * scale;
        if (b.height * scale > getHeight() - 2 * MARGIN) {
            ty = MARGIN - b.y * scale;
        }
        repaint();
    }

    void zoom(double f) {
        zoomAt(new Point(getWidth() / 2, getHeight() / 2), f);
    }

    private void zoomAt(Point p, double f) {
        double ns = Math.max(0.1, Math.min(3.0, scale * f));
        f = ns / scale;
        tx = p.x - (p.x - tx) * f;
        ty = p.y - (p.y - ty) * f;
        scale = ns;
        repaint();
    }

    /** Scrolls so the channel is in view (and selects it). */
    void reveal(String id) {
        GNode n = nodes.get(id);
        selected = id;
        if (n != null && getWidth() > 0) {
            double cx = (n.x + W / 2.0) * scale + tx, cy = (n.y + H / 2.0) * scale + ty;
            if (cx < 0 || cy < 0 || cx > getWidth() || cy > getHeight()) {
                tx += getWidth() / 2.0 - cx;
                ty += getHeight() / 2.0 - cy;
            }
        }
        repaint();
    }

    private Point2D.Double world(Point p) {
        return new Point2D.Double((p.x - tx) / scale, (p.y - ty) / scale);
    }

    private GNode nodeAt(Point p) {
        Point2D.Double w = world(p);
        GNode hit = null;
        for (GNode n : nodes.values()) {
            if (w.x >= n.x && w.x <= n.x + W && w.y >= n.y && w.y <= n.y + H) {
                hit = n;
            }
        }
        return hit;
    }

    private GEdge edgeAt(Point p) {
        Point2D.Double w = world(p);
        double tol = 6 / scale;
        GEdge best = null;
        double bestD = tol;
        for (GEdge e : edges) {
            java.awt.geom.PathIterator it = e.path.getPathIterator(null, 2);
            double[] c = new double[6];
            double px = 0, py = 0;
            while (!it.isDone()) {
                int t = it.currentSegment(c);
                if (t == java.awt.geom.PathIterator.SEG_LINETO) {
                    double d = java.awt.geom.Line2D.ptSegDist(px, py, c[0], c[1], w.x, w.y);
                    if (d < bestD) {
                        bestD = d;
                        best = e;
                    }
                }
                px = c[0];
                py = c[1];
                it.next();
            }
        }
        return best;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        GNode n = nodeAt(e.getPoint());
        if (n != null) {
            if (n.missing()) {
                return "<html><b>Missing channel</b><br>" + Ui.html(n.missingRef) + "<br>Referenced, but no channel has this id / name.</html>";
            }
            DepNode d = n.node;
            StringBuilder sb = new StringBuilder("<html><b>").append(Ui.html(d.name)).append("</b>");
            if (!d.enabled) {
                sb.append(" <font color='#888888'>(disabled)</font>");
            }
            sb.append("<br>Source: ").append(Ui.html(d.sourceType));
            for (String dest : d.destinations) {
                String[] p = dest.split("\\|", -1);
                sb.append("<br>→ ").append(Ui.html(p[1])).append(" <font color='#6B7280'>(").append(Ui.html(p[2])).append(")</font>");
                if (p.length > 3 && "false".equals(p[3])) {
                    sb.append(" <font color='#888888'>disabled</font>");
                }
            }
            if (d.errors + d.warnings > 0) {
                sb.append("<br><font color='#C62828'>").append(Ui.plural(d.errors, "error")).append("</font>, <font color='#E08A00'>")
                        .append(Ui.plural(d.warnings, "warning")).append("</font>");
            }
            if (d.description != null && !d.description.trim().isEmpty()) {
                sb.append("<br><i>").append(Ui.html(d.description.length() > 120 ? d.description.substring(0, 120) + "…" : d.description)).append("</i>");
            }
            sb.append("<br><font color='#6B7280'>Double-click: show its dependencies · right-click: more</font></html>");
            return sb.toString();
        }
        GEdge ge = edgeAt(e.getPoint());
        if (ge != null) {
            return "<html>" + edgeText(ge) + "</html>";
        }
        return null;
    }

    private static String edgeText(GEdge g) {
        DepEdge e = g.e;
        String from = Ui.html(g.from.name()), to = Ui.html(g.to.missing() ? g.to.missingRef : g.to.name());
        if (E_DEPLOY.equals(e.kind)) {
            return "<b>" + from + "</b> depends on <b>" + to + "</b><br>(Channel › Set Dependencies: deployed / started after it)";
        }
        if (E_GLOBALMAP.equals(e.kind)) {
            return "<b>" + from + "</b> writes globalMap variables that <b>" + to + "</b> reads:<br>" + Ui.html(e.detail);
        }
        String how = E_WRITER.equals(e.kind) ? "Channel Writer" : E_ROUTE.equals(e.kind) ? "router.routeMessage" : "channel id used in code";
        return "<b>" + from + "</b>" + (e.connectorName == null ? "" : " › " + Ui.html(e.connectorName)) + "<br>sends to <b>" + to + "</b> (" + how + ")"
                + (e.enabled ? "" : "<br><font color='#888888'>the destination / step is disabled</font>")
                + (e.broken ? "<br><font color='#C62828'>the target channel does not exist</font>" : "");
    }

    private void popup(MouseEvent e) {
        GNode n = nodeAt(e.getPoint());
        if (n != null && !n.missing() && listener != null) {
            selected = n.id;
            repaint();
            listener.popup(n.id, e);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // =====================================================================
    // Painting
    // =====================================================================

    static Color edgeColor(DepEdge e) {
        if (e.broken) {
            return Ui.ERROR;
        }
        if (E_WRITER.equals(e.kind)) {
            return Ui.E_WRITER_COLOR;
        } else if (E_ROUTE.equals(e.kind)) {
            return Ui.E_ROUTE_COLOR;
        } else if (E_DEPLOY.equals(e.kind)) {
            return Ui.E_DEPLOY_COLOR;
        } else if (E_GLOBALMAP.equals(e.kind)) {
            return Ui.E_GLOBALMAP_COLOR;
        }
        return Ui.E_MENTION_COLOR;
    }

    static boolean dashed(DepEdge e) {
        return !e.enabled || e.broken || E_MENTION.equals(e.kind) || E_DEPLOY.equals(e.kind) || E_GLOBALMAP.equals(e.kind);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (nodes.isEmpty()) {
            g.setColor(Ui.MUTED);
            g.setFont(Ui.font().deriveFont(13f));
            drawCentered(g, overview == null ? "Loading…" : emptyText, getWidth() / 2, getHeight() / 2, getWidth() - 80);
            g.dispose();
            return;
        }
        AffineTransform base = g.getTransform();
        g.translate(tx, ty);
        g.scale(scale, scale);

        // hovering a channel fades everything not linked to it; the selected channel only gets bold links
        String active = hover;
        Set<String> near = new HashSet<String>();
        if (active != null) {
            near.add(active);
            for (GEdge e : edges) {
                if (e.from.id.equals(active)) {
                    near.add(e.to.id);
                } else if (e.to.id.equals(active)) {
                    near.add(e.from.id);
                }
            }
        }
        Composite normal = g.getComposite();
        Composite faded = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f);

        // edges
        for (GEdge e : edges) {
            boolean on = active == null || e.from.id.equals(active) || e.to.id.equals(active) || e == hoverEdge;
            g.setComposite(on ? normal : faded);
            Color c = edgeColor(e.e);
            boolean sel = selected != null && (e.from.id.equals(selected) || e.to.id.equals(selected));
            float w = (e == hoverEdge || (active != null && on) || sel) ? 2.6f : 1.6f;
            g.setColor(c);
            g.setStroke(dashed(e.e) ? new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 6f, 4f }, 0f) : new BasicStroke(w));
            g.draw(e.path);
            arrow(g, e.path, c);
        }
        // nodes
        for (GNode n : nodes.values()) {
            boolean on = active == null || near.contains(n.id);
            g.setComposite(on ? normal : faded);
            paintNode(g, n);
        }
        g.setComposite(normal);
        g.setTransform(base);
        paintLegend(g);
        g.dispose();
    }

    private void paintNode(Graphics2D g, GNode n) {
        RoundRectangle2D.Double r = new RoundRectangle2D.Double(n.x, n.y, W, H, 12, 12);
        boolean sel = n.id.equals(selected);
        boolean foc = n.id.equals(focus);
        // shadow
        g.setColor(new Color(0, 0, 0, 22));
        g.fill(new RoundRectangle2D.Double(n.x + 2, n.y + 3, W, H, 12, 12));
        Color fill = Color.WHITE, border = Ui.BORDER, text = new Color(0x1F, 0x29, 0x37);
        Stroke stroke = new BasicStroke(1.2f);
        if (n.missing()) {
            fill = new Color(0xFF, 0xF1, 0xF1);
            border = Ui.ERROR;
            text = Ui.ERROR;
            stroke = new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 5f, 4f }, 0f);
        } else if (!n.node.enabled) {
            fill = new Color(0xF3, 0xF4, 0xF6);
            text = new Color(0x8A, 0x91, 0x9C);
            stroke = new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 3f }, 0f);
        }
        if (foc) {
            fill = n.missing() ? fill : Ui.ACCENT_LIGHT;
        }
        g.setColor(fill);
        g.fill(r);
        // colored strip on the left: source type
        g.setColor(n.missing() ? Ui.ERROR : sourceColor(n.node.sourceType));
        java.awt.Shape clip = g.getClip();
        g.clip(r);
        g.fillRect((int) n.x, (int) n.y, 5, H);
        g.setClip(clip);
        g.setColor(sel || foc ? Ui.ACCENT : border);
        g.setStroke(sel ? new BasicStroke(2.6f) : foc ? new BasicStroke(2f) : stroke);
        g.draw(r);

        // texts
        Font nameFont = Ui.bold(12f);
        g.setFont(nameFont);
        g.setColor(text);
        int badges = n.missing() ? 0 : (n.node.errors > 0 ? 1 : 0) + (n.node.warnings > 0 ? 1 : 0);
        int maxText = W - 18 - badges * 26;
        g.drawString(ellipsis(g.getFontMetrics(), n.missing() ? "Missing channel" : n.node.name, maxText), (int) n.x + 12, (int) n.y + 22);
        g.setFont(Ui.font().deriveFont(10.5f));
        g.setColor(n.missing() ? Ui.ERROR : Ui.MUTED);
        String sub;
        if (n.missing()) {
            sub = n.missingRef;
        } else {
            int dests = n.node.destinations.size();
            sub = n.node.sourceType + " · " + dests + " dest." + (n.node.enabled ? "" : " · disabled");
        }
        g.drawString(ellipsis(g.getFontMetrics(), sub, W - 18), (int) n.x + 12, (int) n.y + 41);

        // problem badges
        if (!n.missing()) {
            int bx = (int) (n.x + W - 8);
            if (n.node.warnings > 0) {
                bx = badge(g, bx, (int) n.y + 8, n.node.warnings, Ui.WARNING);
            }
            if (n.node.errors > 0) {
                badge(g, bx, (int) n.y + 8, n.node.errors, Ui.ERROR);
            }
        }
    }

    /** Draws a count badge with its right edge at x; returns the next x to the left. */
    private static int badge(Graphics2D g, int right, int y, int count, Color c) {
        String s = count > 99 ? "99+" : String.valueOf(count);
        g.setFont(Ui.bold(9.5f));
        FontMetrics fm = g.getFontMetrics();
        int w = Math.max(16, fm.stringWidth(s) + 8);
        g.setColor(c);
        g.fillRoundRect(right - w, y, w, 16, 16, 16);
        g.setColor(Color.WHITE);
        g.drawString(s, right - w + (w - fm.stringWidth(s)) / 2, y + 12);
        return right - w - 4;
    }

    static Color sourceColor(String type) {
        if (type == null) {
            return Ui.MUTED;
        }
        String t = type.toLowerCase();
        if (t.contains("channel reader")) {
            return Ui.E_WRITER_COLOR;
        } else if (t.contains("http") || t.contains("web service")) {
            return new Color(0x00, 0x96, 0x88);
        } else if (t.contains("tcp") || t.contains("mllp")) {
            return new Color(0x43, 0x8E, 0x3F);
        } else if (t.contains("file")) {
            return new Color(0xA1, 0x6D, 0x1B);
        } else if (t.contains("database")) {
            return new Color(0x6D, 0x4C, 0x9F);
        } else if (t.contains("javascript")) {
            return new Color(0xC2, 0x9D, 0x00);
        }
        return new Color(0x7A, 0x86, 0x93);
    }

    private static void arrow(Graphics2D g, Path2D.Double p, Color c) {
        // direction at the end of the curve: from the last control point to the end point
        java.awt.geom.PathIterator it = p.getPathIterator(null);
        double[] co = new double[6];
        double ex = 0, ey = 0, cx = 0, cy = 0;
        while (!it.isDone()) {
            int t = it.currentSegment(co);
            if (t == java.awt.geom.PathIterator.SEG_CUBICTO) {
                cx = co[2];
                cy = co[3];
                ex = co[4];
                ey = co[5];
            } else if (t == java.awt.geom.PathIterator.SEG_MOVETO || t == java.awt.geom.PathIterator.SEG_LINETO) {
                cx = ex;
                cy = ey;
                ex = co[0];
                ey = co[1];
            }
            it.next();
        }
        double a = Math.atan2(ey - cy, ex - cx);
        Path2D.Double h = new Path2D.Double();
        h.moveTo(ex, ey);
        h.lineTo(ex - 10 * Math.cos(a - 0.4), ey - 10 * Math.sin(a - 0.4));
        h.lineTo(ex - 10 * Math.cos(a + 0.4), ey - 10 * Math.sin(a + 0.4));
        h.closePath();
        g.setColor(c);
        g.fill(h);
    }

    private void paintLegend(Graphics2D g) {
        String[][] items = { { "Channel Writer", "0" }, { "router.routeMessage", "1" }, { "Channel id in code", "2" }, { "Deploy dependency", "3" },
                { "Shared globalMap", "4" }, { "Missing / disabled", "5" } };
        Color[] colors = { Ui.E_WRITER_COLOR, Ui.E_ROUTE_COLOR, Ui.E_MENTION_COLOR, Ui.E_DEPLOY_COLOR, Ui.E_GLOBALMAP_COLOR, Ui.ERROR };
        boolean[] dash = { false, false, true, true, true, true };
        g.setFont(Ui.font().deriveFont(11f));
        FontMetrics fm = g.getFontMetrics();
        int x = 12, y = getHeight() - 14;
        int total = 0;
        for (String[] it : items) {
            total += 30 + fm.stringWidth(it[0]) + 14;
        }
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRoundRect(x - 6, y - 14, total + 4, 22, 10, 10);
        g.setColor(Ui.BORDER);
        g.drawRoundRect(x - 6, y - 14, total + 4, 22, 10, 10);
        for (int i = 0; i < items.length; i++) {
            g.setColor(colors[i]);
            g.setStroke(dash[i] ? new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 5f, 4f }, 0f) : new BasicStroke(2f));
            g.drawLine(x, y - 3, x + 22, y - 3);
            g.setColor(Ui.MUTED);
            g.drawString(items[i][0], x + 28, y + 1);
            x += 30 + fm.stringWidth(items[i][0]) + 14;
        }
        String zoom = Math.round(scale * 100) + "%";
        g.setColor(Ui.MUTED);
        g.drawString(zoom, getWidth() - fm.stringWidth(zoom) - 12, getHeight() - 12);
    }

    private static String ellipsis(FontMetrics fm, String s, int max) {
        if (s == null) {
            return "";
        }
        if (fm.stringWidth(s) <= max) {
            return s;
        }
        String e = "…";
        int n = s.length();
        while (n > 1 && fm.stringWidth(s.substring(0, n) + e) > max) {
            n--;
        }
        return s.substring(0, n) + e;
    }

    private static void drawCentered(Graphics2D g, String s, int cx, int cy, int maxW) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (cur.length() > 0 && fm.stringWidth(cur + " " + w) > maxW) {
                lines.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(cur.length() == 0 ? "" : " ").append(w);
        }
        lines.add(cur.toString());
        int y = cy - lines.size() * fm.getHeight() / 2 + fm.getAscent();
        for (String l : lines) {
            g.drawString(l, cx - fm.stringWidth(l) / 2, y);
            y += fm.getHeight();
        }
    }
}
