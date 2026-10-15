package com.mirth.connect.plugins.depexplorer.server;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.mirth.connect.plugins.depexplorer.DepEdge;
import com.mirth.connect.plugins.depexplorer.DepNode;
import com.mirth.connect.plugins.depexplorer.DepOverview;
import com.mirth.connect.plugins.depexplorer.DepQuery;
import com.mirth.connect.plugins.depexplorer.DepRef;
import com.mirth.connect.plugins.depexplorer.DepReport;
import com.mirth.connect.plugins.depexplorer.DepResult;

/**
 * An immutable snapshot of all scans with everything derived from them: the channel flow graph, broken
 * references, unused items and the answers to "who uses it" queries.
 */
final class Analysis {

    /** Most rows a query / report category returns. */
    static final int MAX_ROWS = 3000;

    static final class Lib {
        String id;
        String name;
        boolean includeNew;
        Set<String> enabled = new HashSet<String>();
        Set<String> disabled = new HashSet<String>();
        List<String> templateIds = new ArrayList<String>();

        boolean enabledFor(String channelId) {
            return enabled.contains(channelId) || (includeNew && !disabled.contains(channelId));
        }
    }

    long scannedAt = System.currentTimeMillis();
    long scanMillis;
    int rescanned;
    long cacheKb;

    final List<ItemScan> channels;
    final List<ItemScan> templates;
    final ItemScan globals;
    final List<ItemScan> all = new ArrayList<ItemScan>();
    final Set<String> configKeys;
    final List<Lib> libs;
    final List<String[]> deps;

    final Map<String, ItemScan> channelById = new HashMap<String, ItemScan>();
    final Map<String, ItemScan> channelByName = new HashMap<String, ItemScan>();
    final Map<String, Lib> libById = new HashMap<String, Lib>();
    /** function -> templates defining it (only templates added to scripts) */
    final Map<String, List<ItemScan>> fnDefs = new HashMap<String, List<ItemScan>>();
    final Map<String, List<ItemScan>> fnDefsAll = new HashMap<String, List<ItemScan>>();
    /** map variable -> maps it is written to */
    final Map<String, Set<Byte>> written = new HashMap<String, Set<Byte>>();
    final Set<String> read = new HashSet<String>();
    final Set<String> called = new HashSet<String>();
    final Set<String> configUsed = new HashSet<String>();
    final Set<String> destinationNames = new HashSet<String>();

    final List<DepEdge> edges = new ArrayList<DepEdge>();
    final List<DepRef> problems = new ArrayList<DepRef>();
    final List<DepRef> unused = new ArrayList<DepRef>();
    boolean truncated;
    final Map<String, int[]> problemCount = new HashMap<String, int[]>();

    Analysis(List<ItemScan> channels, List<ItemScan> templates, ItemScan globals, Set<String> configKeys, List<Lib> libs, List<String[]> deps) {
        Comparator<ItemScan> byName = new Comparator<ItemScan>() {
            @Override
            public int compare(ItemScan a, ItemScan b) {
                return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
            }
        };
        Collections.sort(channels, byName);
        Collections.sort(templates, byName);
        this.channels = channels;
        this.templates = templates;
        this.globals = globals;
        this.configKeys = configKeys;
        this.libs = libs;
        this.deps = deps;
        all.addAll(channels);
        all.addAll(templates);
        if (globals != null) {
            all.add(globals);
        }
        index();
        buildEdges();
        buildProblems();
        buildUnused();
        fillSnippets(problems);
        fillSnippets(unused);
    }

    // =====================================================================
    // Indexes
    // =====================================================================

    private void index() {
        for (ItemScan c : channels) {
            channelById.put(c.id, c);
            if (!channelByName.containsKey(c.name)) {
                channelByName.put(c.name, c);
            }
            for (String[] d : c.destinations) {
                destinationNames.add(d[1]);
            }
        }
        for (Lib l : libs) {
            libById.put(l.id, l);
        }
        for (ItemScan t : templates) {
            for (String f : t.functions) {
                list(fnDefsAll, f).add(t);
                if (t.addToScripts) {
                    list(fnDefs, f).add(t);
                }
            }
        }
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                String k = s.key[i];
                switch (s.kind[i]) {
                    case ItemScan.MAP_W:
                        Set<Byte> m = written.get(k);
                        if (m == null) {
                            m = new TreeSet<Byte>();
                            written.put(k, m);
                        }
                        m.add(s.map[i]);
                        break;
                    case ItemScan.MAP_R:
                    case ItemScan.MAP_SOFT:
                    case ItemScan.LOOKUP:
                        read.add(k);
                        configUsed.add(k);
                        break;
                    case ItemScan.VELO:
                        read.add(k);
                        configUsed.add(k);
                        int dot = k.indexOf('.');
                        if (dot > 0) {
                            read.add(k.substring(0, dot));
                            configUsed.add(k.substring(0, dot));
                        }
                        break;
                    case ItemScan.CFG:
                        configUsed.add(k);
                        break;
                    case ItemScan.CALL:
                        if (!(s.type.equals(T_TEMPLATE) && s.functions.contains(k))) {
                            called.add(k);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static <K, V> List<V> list(Map<K, List<V>> m, K k) {
        List<V> l = m.get(k);
        if (l == null) {
            l = new ArrayList<V>();
            m.put(k, l);
        }
        return l;
    }

    // =====================================================================
    // Flow graph
    // =====================================================================

    private void buildEdges() {
        Map<String, DepEdge> seen = new LinkedHashMap<String, DepEdge>();
        for (ItemScan c : channels) {
            Set<String> handled = new HashSet<String>();
            for (int i = 0; i < c.n; i++) {
                byte k = c.kind[i];
                if (k == ItemScan.WRITER || k == ItemScan.ROUTE_ID || k == ItemScan.ROUTE_NAME) {
                    handled.add(c.loc[i] + "|" + c.key[i]);
                }
            }
            for (int i = 0; i < c.n; i++) {
                byte k = c.kind[i];
                String kind;
                ItemScan target;
                if (k == ItemScan.WRITER) {
                    kind = E_WRITER;
                    target = channelById.get(c.key[i]);
                } else if (k == ItemScan.ROUTE_ID || k == ItemScan.ROUTE_NAME) {
                    kind = E_ROUTE;
                    target = resolveChannel(c.key[i]);
                } else if (k == ItemScan.UUID) {
                    target = channelById.get(c.key[i]);
                    if (target == null || target == c || handled.contains(c.loc[i] + "|" + c.key[i])) {
                        continue;
                    }
                    kind = E_MENTION;
                } else {
                    continue;
                }
                ItemScan.Loc l = c.locs.get(c.loc[i]);
                String tkey = target == null ? "?" + c.key[i] : target.id;
                String sk = c.id + "|" + tkey + "|" + kind + "|" + l.connector;
                if (seen.containsKey(sk)) {
                    continue;
                }
                DepEdge e = new DepEdge();
                e.fromId = c.id;
                e.toId = target == null ? null : target.id;
                e.targetRef = c.key[i];
                e.kind = kind;
                e.connector = l.connector;
                e.connectorName = l.connectorName;
                e.enabled = l.connectorEnabled && l.stepEnabled;
                e.broken = target == null;
                seen.put(sk, e);
            }
        }
        for (String[] d : deps) {
            ItemScan from = channelById.get(d[0]);
            ItemScan to = channelById.get(d[1]);
            if (from == null) {
                continue;
            }
            DepEdge e = new DepEdge();
            e.fromId = d[0];
            e.toId = to == null ? null : d[1];
            e.targetRef = d[1];
            e.kind = E_DEPLOY;
            e.broken = to == null;
            seen.put(d[0] + "|" + d[1] + "|" + E_DEPLOY, e);
        }
        // globalMap variables written by one channel and read by another
        Map<String, Set<String>> writers = new HashMap<String, Set<String>>();
        Map<String, Set<String>> readers = new HashMap<String, Set<String>>();
        for (ItemScan c : channels) {
            for (int i = 0; i < c.n; i++) {
                if (c.kind[i] == ItemScan.MAP_W && c.map[i] == ItemScan.M_GLOBAL) {
                    set(writers, c.key[i]).add(c.id);
                } else if ((c.kind[i] == ItemScan.MAP_R && c.map[i] == ItemScan.M_GLOBAL) || c.kind[i] == ItemScan.LOOKUP || c.kind[i] == ItemScan.VELO) {
                    set(readers, c.key[i]).add(c.id);
                }
            }
        }
        Map<String, Set<String>> pairs = new TreeMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> w : writers.entrySet()) {
            Set<String> r = readers.get(w.getKey());
            if (r == null) {
                continue;
            }
            for (String from : w.getValue()) {
                for (String to : r) {
                    if (!from.equals(to)) {
                        set(pairs, from + "|" + to).add(w.getKey());
                    }
                }
            }
        }
        for (Map.Entry<String, Set<String>> p : pairs.entrySet()) {
            String[] ft = p.getKey().split("\\|");
            DepEdge e = new DepEdge();
            e.fromId = ft[0];
            e.toId = ft[1];
            e.targetRef = ft[1];
            e.kind = E_GLOBALMAP;
            e.detail = "globalMap: " + join(p.getValue(), 8);
            seen.put(p.getKey() + "|" + E_GLOBALMAP, e);
        }
        edges.addAll(seen.values());
    }

    ItemScan resolveChannel(String key) {
        ItemScan t = channelByName.get(key);
        return t != null ? t : channelById.get(key);
    }

    private static Set<String> set(Map<String, Set<String>> m, String k) {
        Set<String> s = m.get(k);
        if (s == null) {
            s = new TreeSet<String>();
            m.put(k, s);
        }
        return s;
    }

    private static String join(Set<String> s, int max) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String x : s) {
            if (i++ == max) {
                sb.append(", … (").append(s.size()).append(')');
                break;
            }
            sb.append(i > 1 ? ", " : "").append(x);
        }
        return sb.toString();
    }

    // =====================================================================
    // Broken references
    // =====================================================================

    /**
     * Categories that can repeat a lot (e.g. a variable used in every channel): one row per name, listing the
     * items; the "who uses it" search shows every place.
     */
    static final Set<String> PER_NAME = new HashSet<String>(java.util.Arrays.asList("Unresolved variable", "Map variable never read",
            "Map variable never written", "Unknown function"));

    /** Collects findings, one per (category, item, key) - or per (category, key) - and counts repeated places. */
    private final class Findings {
        final Map<String, DepRef> rows = new LinkedHashMap<String, DepRef>();
        final Map<String, Integer> counts = new HashMap<String, Integer>();
        final Map<String, Set<String>> owners = new HashMap<String, Set<String>>();
        final Map<String, Integer> perCategory = new HashMap<String, Integer>();
        final List<DepRef> out;
        final boolean countPerChannel;
        final Set<String> counted = new HashSet<String>();

        boolean aggregate = true;

        Findings(List<DepRef> out, boolean countPerChannel) {
            this.out = out;
            this.countPerChannel = countPerChannel;
        }

        void add(String severity, String category, ItemScan s, int i, String key, String message) {
            if (countPerChannel && s.type.equals(T_CHANNEL) && counted.add(s.id + "|" + category + "|" + key)) {
                int[] c = problemCount.get(s.id);
                if (c == null) {
                    c = new int[2];
                    problemCount.put(s.id, c);
                }
                c[S_ERROR.equals(severity) ? 0 : 1]++;
            }
            boolean perName = aggregate && PER_NAME.contains(category);
            String k = perName ? category + "||" + key : category + "|" + s.id + "|" + key;
            DepRef r = rows.get(k);
            if (r != null) {
                counts.put(k, counts.get(k) + 1);
                if (perName) {
                    owners.get(k).add(s.name);
                }
                return;
            }
            Integer pc = perCategory.get(category);
            if (pc != null && pc >= MAX_ROWS) {
                truncated = true;
                return;
            }
            perCategory.put(category, pc == null ? 1 : pc + 1);
            r = i >= 0 ? row(s, i, category, category) : owner(s, category, category);
            r.severity = severity;
            r.category = category;
            r.key = key;
            r.message = message;
            rows.put(k, r);
            counts.put(k, 1);
            if (perName) {
                Set<String> o = new LinkedHashSet<String>();
                o.add(s.name);
                owners.put(k, o);
            }
        }

        void done() {
            for (Map.Entry<String, DepRef> e : rows.entrySet()) {
                int c = counts.get(e.getKey());
                Set<String> o = owners.get(e.getKey());
                DepRef r = e.getValue();
                if (o != null && o.size() > 1) {
                    r.message += " Found in " + o.size() + " items, " + c + " places: " + join(o, 6) + ". The first place is shown; search for the name to see all.";
                    r.ownerName = r.ownerName + " (+" + (o.size() - 1) + " more)";
                } else if (c > 1) {
                    r.message += " (" + c + " places in this item)";
                }
                out.add(r);
            }
        }
    }

    private void buildProblems() {
        Findings f = new Findings(problems, true);
        for (ItemScan s : all) {
            check(f, s);
        }
        // the same function defined in several templates
        for (Map.Entry<String, List<ItemScan>> e : fnDefs.entrySet()) {
            if (e.getValue().size() > 1) {
                List<String> names = new ArrayList<String>();
                for (ItemScan t : e.getValue()) {
                    names.add(templateLabel(t));
                }
                for (ItemScan t : e.getValue()) {
                    f.add(S_WARNING, "Function defined more than once", t, defIndex(t, e.getKey()), e.getKey(),
                            e.getKey() + "() is defined in " + names.size() + " code templates: " + names + ". Which one is used depends on the load order.");
                }
            }
        }
        f.done();
    }

    /** Every problem of one item, one row per place-group (not aggregated across items). */
    List<DepRef> problemsOf(ItemScan s) {
        List<DepRef> out = new ArrayList<DepRef>();
        Findings f = new Findings(out, false);
        f.aggregate = false;
        check(f, s);
        f.done();
        return out;
    }

    private void check(Findings f, ItemScan s) {
        {
            boolean isChannel = s.type.equals(T_CHANNEL);
            for (int i = 0; i < s.n; i++) {
                String k = s.key[i];
                ItemScan.Loc l = s.locs.get(s.loc[i]);
                switch (s.kind[i]) {
                    case ItemScan.CFG:
                        if (!configKeys.contains(k)) {
                            f.add(S_ERROR, "Missing Configuration Map key", s, i, k, "'" + k + "' is read from the Configuration Map, but the key is not defined there.");
                        }
                        break;
                    case ItemScan.LOOKUP:
                        if (!resolvable(k)) {
                            f.add(S_WARNING, "Unresolved variable", s, i, k,
                                    "$('" + k + "') finds no Configuration Map key and no map variable that is written anywhere.");
                        }
                        break;
                    case ItemScan.VELO:
                        if (!resolvableVelocity(k)) {
                            f.add(S_WARNING, "Unresolved variable", s, i, k,
                                    "${" + k + "} finds no Configuration Map key and no map variable that is written anywhere.");
                        }
                        break;
                    case ItemScan.MAP_R:
                        byte m = s.map[i];
                        if (m != ItemScan.M_RESPONSE && m != ItemScan.M_SOURCE && !written.containsKey(k) && !Builtins.MAP_KEYS.contains(k)) {
                            f.add(S_WARNING, "Map variable never written", s, i, k,
                                    ItemScan.MAPS[m] + " '" + k + "' is read, but nothing writes it (no put, no Mapper step).");
                        }
                        break;
                    case ItemScan.WRITER:
                    case ItemScan.ROUTE_ID:
                    case ItemScan.ROUTE_NAME: {
                        ItemScan t = s.kind[i] == ItemScan.WRITER ? channelById.get(k) : resolveChannel(k);
                        String how = s.kind[i] == ItemScan.WRITER ? "The Channel Writer sends to channel id " + k
                                : s.kind[i] == ItemScan.ROUTE_ID ? "router.routeMessageByChannelId sends to channel id " + k : "router.routeMessage sends to channel '" + k + "'";
                        if (t == null) {
                            f.add(S_ERROR, "Missing channel", s, i, k, how + ", which does not exist.");
                        } else if (!t.enabled) {
                            DepRef r = null;
                            f.add(S_WARNING, "Disabled target channel", s, i, t.name,
                                    how + (s.kind[i] == ItemScan.ROUTE_NAME ? "" : " ('" + t.name + "')") + ", which is disabled.");
                            r = f.rows.get("Disabled target channel|" + s.id + "|" + t.name);
                            if (r != null) {
                                target(r, t);
                            }
                        }
                        break;
                    }
                    case ItemScan.CALL:
                        call(f, s, i, k, l, isChannel);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private void call(Findings f, ItemScan s, int i, String fn, ItemScan.Loc l, boolean isChannel) {
        if (Builtins.FUNCTIONS.contains(fn) || Scanner.KEYWORDS.contains(fn) || s.localNames.contains(fn)) {
            return;
        }
        List<ItemScan> defs = fnDefs.get(fn);
        if (defs == null || defs.isEmpty()) {
            List<ItemScan> dd = fnDefsAll.get(fn);
            if (dd != null && !dd.isEmpty()) {
                f.add(S_ERROR, "Function not added to scripts", s, i, fn, fn + "() is defined in " + templateLabel(dd.get(0))
                        + ", a Drag-and-Drop code template; such templates are not added to scripts.");
            } else if (isChannel || s.type.equals(T_GLOBAL) || s.type.equals(T_TEMPLATE)) {
                f.add(S_WARNING, "Unknown function", s, i, fn,
                        fn + "() is not defined in any code template or in this " + s.type.toLowerCase() + ", and is not a known JavaScript / Mirth function.");
            }
            return;
        }
        if (s.type.equals(T_TEMPLATE)) {
            return; // availability depends on the channel that includes the template
        }
        List<ItemScan> available = new ArrayList<ItemScan>();
        if (isChannel) {
            for (ItemScan t : defs) {
                Lib lib = t.libraryId == null ? null : libById.get(t.libraryId);
                if (lib == null || lib.enabledFor(s.id)) {
                    available.add(t);
                }
            }
            if (available.isEmpty()) {
                ItemScan t = defs.get(0);
                DepRef r = null;
                f.add(S_ERROR, "Code template library not enabled", s, i, fn, fn + "() is defined in " + templateLabel(t) + ", but the library '"
                        + t.libraryName + "' is not enabled for this channel (Channel › Set Dependencies / Code Template Library › Channels).");
                r = f.rows.get("Code template library not enabled|" + s.id + "|" + fn);
                if (r != null) {
                    r.targetType = T_TEMPLATE;
                    r.targetId = t.id;
                    r.targetName = t.name;
                }
                return;
            }
        } else {
            available.addAll(defs);
        }
        if (l.context == null) {
            return;
        }
        for (ItemScan t : available) {
            if (t.contexts == null || t.contexts.contains(l.context)) {
                return;
            }
        }
        ItemScan t = available.get(0);
        f.add(S_ERROR, "Function not available here", s, i, fn, fn + "() comes from " + templateLabel(t) + ", which is not enabled for "
                + contextName(l.context) + " (Code Templates › " + t.name + " › Context).");
        DepRef r = f.rows.get("Function not available here|" + s.id + "|" + fn);
        if (r != null) {
            r.targetType = T_TEMPLATE;
            r.targetId = t.id;
            r.targetName = t.name;
        }
    }

    private boolean resolvable(String k) {
        return configKeys.contains(k) || written.containsKey(k) || Builtins.MAP_KEYS.contains(k) || destinationNames.contains(k) || k.matches("d\\d+");
    }

    private boolean resolvableVelocity(String k) {
        if (resolvable(k)) {
            return true;
        }
        int dot = k.indexOf('.');
        String first = dot > 0 ? k.substring(0, dot) : k;
        return Builtins.VELOCITY.contains(first) || resolvable(first);
    }

    private static int defIndex(ItemScan t, String fn) {
        for (int i = 0; i < t.n; i++) {
            if (t.kind[i] == ItemScan.DEF && t.key[i].equals(fn)) {
                return i;
            }
        }
        return -1;
    }

    static String templateLabel(ItemScan t) {
        return "code template '" + t.name + "'" + (t.libraryName == null ? "" : " (library '" + t.libraryName + "')");
    }

    static String contextName(String context) {
        String s = context.toLowerCase().replace('_', ' ');
        if (s.startsWith("global ")) {
            return "Global " + Character.toUpperCase(s.charAt(7)) + s.substring(8) + " scripts";
        }
        if (s.startsWith("channel ")) {
            return "Channel " + Character.toUpperCase(s.charAt(8)) + s.substring(9) + " scripts";
        }
        if (context.equals("SOURCE_RECEIVER")) {
            return "Source receiver scripts";
        }
        if (context.equals("SOURCE_FILTER_TRANSFORMER")) {
            return "Source filter / transformer";
        }
        if (context.equals("DESTINATION_FILTER_TRANSFORMER")) {
            return "Destination filter / transformer";
        }
        if (context.equals("DESTINATION_DISPATCHER")) {
            return "Destination dispatcher scripts";
        }
        if (context.equals("DESTINATION_RESPONSE_TRANSFORMER")) {
            return "Destination response transformer";
        }
        return s;
    }

    // =====================================================================
    // Unused
    // =====================================================================

    private void buildUnused() {
        Findings f = new Findings(unused, false);
        // Configuration Map keys
        for (String k : configKeys) {
            if (!configUsed.contains(k)) {
                DepRef r = new DepRef();
                r.ownerType = T_CONFIG;
                r.ownerId = k;
                r.ownerName = k;
                r.location = "Settings › Configuration Map";
                r.group = "Unused Configuration Map key";
                r.relation = r.group;
                String key = "Unused Configuration Map key|config|" + k;
                f.rows.put(key, r);
                f.counts.put(key, 1);
                r.severity = S_INFO;
                r.category = r.group;
                r.key = k;
                r.message = "No channel, code template or global script reads '" + k + "'.";
            }
        }
        // code template functions
        for (ItemScan t : templates) {
            for (String fn : t.functions) {
                if (!called.contains(fn)) {
                    f.add(S_INFO, "Unused code template function", t, defIndex(t, fn), fn, fn + "() is not called by any channel, global script or other code template.");
                }
            }
        }
        // libraries enabled for no channel
        for (Lib l : libs) {
            if (!l.includeNew && l.enabled.isEmpty() && !l.templateIds.isEmpty()) {
                DepRef r = new DepRef();
                r.ownerType = T_LIBRARY;
                r.ownerId = l.id;
                r.ownerName = l.name;
                r.location = "Channels › Edit Code Templates › " + l.name;
                r.severity = S_INFO;
                r.category = "Library not enabled for any channel";
                r.group = r.category;
                r.relation = r.category;
                r.key = l.name;
                r.message = "The library '" + l.name + "' (" + l.templateIds.size() + " templates) is not enabled for any channel; only global scripts can use it.";
                f.rows.put(r.category + "|" + l.id, r);
                f.counts.put(r.category + "|" + l.id, 1);
            }
        }
        // channels that only other channels can feed, but nobody does
        Set<String> fed = new HashSet<String>();
        for (DepEdge e : edges) {
            if (e.toId != null && !E_DEPLOY.equals(e.kind) && !E_GLOBALMAP.equals(e.kind)) {
                fed.add(e.toId);
            }
        }
        for (ItemScan c : channels) {
            if ("Channel Reader".equals(c.sourceType) && !fed.contains(c.id)) {
                f.add(S_INFO, "Channel nobody sends to", c, -1, c.name,
                        "The source is a Channel Reader, so only other channels can send messages to it, but no channel does.");
            }
        }
        // map variables written but never read
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                if (s.kind[i] == ItemScan.MAP_W && s.map[i] != ItemScan.M_RESPONSE && !read.contains(s.key[i])) {
                    f.add(S_INFO, "Map variable never read", s, i, s.key[i],
                            ItemScan.MAPS[s.map[i]] + " '" + s.key[i] + "' is written, but nothing reads it ($(), get, ${…}, metadata columns).");
                }
            }
        }
        f.done();
    }

    // =====================================================================
    // Rows
    // =====================================================================

    /** A row for reference i of item s. */
    DepRef row(ItemScan s, int i, String group, String relation) {
        DepRef r = owner(s, group, relation);
        if (i >= 0) {
            ItemScan.Loc l = s.locs.get(s.loc[i]);
            r.connector = l.connector;
            r.connectorName = l.connectorName;
            r.part = l.part;
            r.step = l.step;
            r.stepName = l.stepName;
            r.field = l.field;
            r.location = l.label;
            r.line = s.line[i];
            r.key = s.key[i];
            r.snippet = "\u0000" + s.loc[i]; // filled in by fillSnippets
        }
        return r;
    }

    DepRef owner(ItemScan s, String group, String relation) {
        DepRef r = new DepRef();
        r.group = group;
        r.relation = relation;
        r.ownerType = s.type;
        r.ownerId = s.id;
        r.ownerName = s.type.equals(T_TEMPLATE) && s.libraryName != null ? s.libraryName + " › " + s.name : s.name;
        r.location = s.type.equals(T_CHANNEL) ? "Channel" : s.name;
        return r;
    }

    static void target(DepRef r, ItemScan t) {
        r.targetType = t.type;
        r.targetId = t.id;
        r.targetName = t.name;
    }

    /** Replaces the leaf markers in the rows by the code line, unpacking each item's texts once. */
    void fillSnippets(List<DepRef> rows) {
        Map<String, String[]> texts = new HashMap<String, String[]>();
        Map<String, ItemScan> byId = new HashMap<String, ItemScan>();
        for (ItemScan s : all) {
            byId.put(s.type + "|" + s.id, s);
        }
        for (DepRef r : rows) {
            if (r.snippet == null || !r.snippet.startsWith("\u0000")) {
                continue;
            }
            int leaf = Integer.parseInt(r.snippet.substring(1));
            String key = r.ownerType + "|" + r.ownerId;
            String[] t = texts.get(key);
            if (t == null) {
                ItemScan s = byId.get(key);
                t = s == null ? new String[0] : s.texts();
                texts.put(key, t);
            }
            r.snippet = leaf < t.length && t[leaf] != null ? JsText.line(t[leaf], Math.max(1, r.line)) : "";
        }
    }

    // =====================================================================
    // Overview
    // =====================================================================

    DepOverview overview() {
        DepOverview o = new DepOverview();
        o.scannedAt = scannedAt;
        o.scanMillis = scanMillis;
        o.rescanned = rescanned;
        o.cacheKb = cacheKb;
        for (ItemScan c : channels) {
            DepNode n = new DepNode();
            n.id = c.id;
            n.name = c.name;
            n.enabled = c.enabled;
            n.sourceType = c.sourceType;
            n.description = c.description == null ? null : (c.description.length() > 300 ? c.description.substring(0, 300) + "…" : c.description);
            for (String[] d : c.destinations) {
                n.destinations.add(d[0] + "|" + d[1] + "|" + d[2] + "|" + d[3]);
            }
            int[] pc = problemCount.get(c.id);
            if (pc != null) {
                n.errors = pc[0];
                n.warnings = pc[1];
            }
            o.channels.add(n);
        }
        o.edges.addAll(edges);
        o.configKeys.addAll(configKeys);
        Set<String> missing = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                if (s.kind[i] == ItemScan.CFG && !configKeys.contains(s.key[i])) {
                    missing.add(s.key[i]);
                }
            }
        }
        o.missingConfigKeys.addAll(missing);
        Set<String> fns = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (ItemScan t : templates) {
            for (String fn : t.functions) {
                fns.add(fn + "|" + t.name + "|" + (t.libraryName == null ? "" : t.libraryName));
            }
            o.templates.add(t.id + "|" + t.name + "|" + (t.libraryName == null ? "" : t.libraryName));
        }
        o.functions.addAll(fns);
        Map<String, Set<String>> vars = new TreeMap<String, Set<String>>(String.CASE_INSENSITIVE_ORDER);
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                if (s.kind[i] == ItemScan.MAP_W || s.kind[i] == ItemScan.MAP_R) {
                    Set<String> m = vars.get(s.key[i]);
                    if (m == null) {
                        m = new TreeSet<String>();
                        vars.put(s.key[i], m);
                    }
                    m.add(ItemScan.MAPS[s.map[i]]);
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : vars.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (String m : e.getValue()) {
                sb.append(sb.length() == 0 ? "" : ", ").append(m);
            }
            o.mapVariables.add(e.getKey() + "|" + sb);
        }
        if (globals != null) {
            for (ItemScan.Loc l : globals.locs) {
                o.globalScripts.add(l.field);
            }
        }
        for (DepRef r : problems) {
            if (S_ERROR.equals(r.severity)) {
                o.errors++;
            } else {
                o.warnings++;
            }
        }
        o.unused = unused.size();
        return o;
    }

    DepReport report() {
        DepReport r = new DepReport();
        r.scannedAt = scannedAt;
        r.problems.addAll(problems);
        r.unused.addAll(unused);
        r.truncated = truncated;
        return r;
    }

    // =====================================================================
    // Queries
    // =====================================================================

    DepResult query(DepQuery q) {
        String type = q.type == null ? Q_CONFIG : q.type;
        String key = q.key == null ? "" : q.key.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Enter a name to search for.");
        }
        DepResult res = new DepResult();
        res.type = type;
        res.key = key;
        res.title = key;
        List<DepRef> rows = new ArrayList<DepRef>();
        if (type.equals(Q_CONFIG)) {
            configQuery(key, res, rows);
        } else if (type.equals(Q_FUNCTION)) {
            functionQuery(key, res, rows);
        } else if (type.equals(Q_MAPVAR)) {
            mapQuery(key, res, rows);
        } else if (type.equals(Q_CHANNEL)) {
            channelQuery(key, res, rows);
        } else if (type.equals(Q_TEXT)) {
            textQuery(key, res, rows);
        } else {
            throw new IllegalArgumentException("Unknown query type: " + type);
        }
        if (rows.size() > MAX_ROWS) {
            rows = new ArrayList<DepRef>(rows.subList(0, MAX_ROWS));
            res.truncated = true;
        }
        fillSnippets(rows);
        res.rows = rows;
        return res;
    }

    private static String ownerGroup(ItemScan s) {
        return s.type.equals(T_CHANNEL) ? "Channels" : s.type.equals(T_TEMPLATE) ? "Code Templates" : "Global Scripts";
    }

    private void configQuery(String key, DepResult res, List<DepRef> rows) {
        boolean defined = configKeys.contains(key);
        res.subtitle = defined ? "Configuration Map key" : "Not defined in the Configuration Map";
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                byte k = s.kind[i];
                String rel = null;
                if (k == ItemScan.CFG && s.key[i].equals(key)) {
                    rel = "Reads ($cfg)";
                } else if (k == ItemScan.LOOKUP && s.key[i].equals(key)) {
                    rel = "Reads ($())";
                } else if (k == ItemScan.VELO && (s.key[i].equals(key) || s.key[i].startsWith(key + "."))) {
                    rel = "Uses (${…})";
                }
                if (rel != null) {
                    rows.add(row(s, i, ownerGroup(s), rel));
                }
            }
        }
        if (written.containsKey(key)) {
            res.subtitle += " · also a map variable – see Map variable search";
        }
        problemsFor(key, null, rows);
    }

    private void functionQuery(String fn, DepResult res, List<DepRef> rows) {
        List<ItemScan> defs = fnDefsAll.get(fn);
        res.subtitle = defs == null ? "Not defined in any code template" : "Defined in " + defs.size() + " code template" + (defs.size() == 1 ? "" : "s");
        if (defs != null) {
            for (ItemScan t : defs) {
                rows.add(row(t, defIndex(t, fn), "Defined in", "Defines"));
                Lib l = t.libraryId == null ? null : libById.get(t.libraryId);
                if (l != null) {
                    res.subtitle += " · library '" + l.name + "'" + (l.includeNew ? " (all new channels)" : " (" + l.enabled.size() + " channels)");
                }
            }
        }
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                if (s.key[i].equals(fn)) {
                    if (s.kind[i] == ItemScan.CALL) {
                        rows.add(row(s, i, "Called from " + ownerGroup(s).toLowerCase(), "Calls"));
                    } else if (s.kind[i] == ItemScan.DEF && !s.type.equals(T_TEMPLATE)) {
                        rows.add(row(s, i, "Defined in", "Defines (local function)"));
                    }
                }
            }
        }
        problemsFor(fn, null, rows);
    }

    private void mapQuery(String key, DepResult res, List<DepRef> rows) {
        Set<Byte> maps = written.get(key);
        StringBuilder sb = new StringBuilder();
        if (maps != null) {
            for (Byte m : maps) {
                sb.append(sb.length() == 0 ? "" : ", ").append(ItemScan.MAPS[m]);
            }
        }
        res.subtitle = maps == null ? "Never written" : "Written to " + sb;
        if (configKeys.contains(key)) {
            res.subtitle += " · also a Configuration Map key";
        }
        for (ItemScan s : all) {
            for (int i = 0; i < s.n; i++) {
                String k = s.key[i];
                byte kind = s.kind[i];
                if (kind == ItemScan.MAP_W && k.equals(key)) {
                    rows.add(row(s, i, "Written by", "Writes (" + ItemScan.MAPS[s.map[i]] + ")"));
                } else if (kind == ItemScan.MAP_R && k.equals(key)) {
                    rows.add(row(s, i, "Read by", "Reads (" + ItemScan.MAPS[s.map[i]] + ")"));
                } else if (kind == ItemScan.LOOKUP && k.equals(key)) {
                    rows.add(row(s, i, "Read by", "Reads ($())"));
                } else if (kind == ItemScan.VELO && (k.equals(key) || k.startsWith(key + "."))) {
                    rows.add(row(s, i, "Read by", "Uses (${…})"));
                } else if (kind == ItemScan.MAP_SOFT && k.equals(key)) {
                    rows.add(row(s, i, "Read by", "Used by setting"));
                }
            }
        }
        problemsFor(key, null, rows);
    }

    private void channelQuery(String key, DepResult res, List<DepRef> rows) {
        ItemScan c = resolveChannel(key);
        if (c == null) {
            throw new IllegalArgumentException("Channel not found: " + key);
        }
        res.title = c.name;
        res.key = c.id;
        res.subtitle = (c.enabled ? "" : "Disabled · ") + "Source: " + c.sourceType + " · " + c.destinations.size() + " destination"
                + (c.destinations.size() == 1 ? "" : "s");
        // incoming
        for (DepEdge e : edges) {
            if (c.id.equals(e.toId) && !E_DEPLOY.equals(e.kind)) {
                ItemScan from = channelById.get(e.fromId);
                if (from == null) {
                    continue;
                }
                if (E_GLOBALMAP.equals(e.kind)) {
                    DepRef r = owner(from, "Shared map variables", "Writes " + e.detail + " read here");
                    target(r, from);
                    rows.add(r);
                    continue;
                }
                int i = edgeRef(from, e);
                DepRef r = i >= 0 ? row(from, i, "Receives messages from", e.kind) : owner(from, "Receives messages from", e.kind);
                r.message = (e.enabled ? "" : "(disabled) ") + from.name + (e.connectorName == null ? "" : " › " + e.connectorName);
                target(r, from);
                rows.add(r);
            }
        }
        // outgoing
        for (DepEdge e : edges) {
            if (c.id.equals(e.fromId) && !E_DEPLOY.equals(e.kind)) {
                ItemScan to = e.toId == null ? null : channelById.get(e.toId);
                if (E_GLOBALMAP.equals(e.kind)) {
                    DepRef r = owner(c, "Shared map variables", "Read by " + (to == null ? "?" : to.name) + ": " + e.detail);
                    if (to != null) {
                        target(r, to);
                    }
                    rows.add(r);
                    continue;
                }
                int i = edgeRef(c, e);
                DepRef r = i >= 0 ? row(c, i, "Sends messages to", e.kind) : owner(c, "Sends messages to", e.kind);
                r.message = to == null ? "Missing channel: " + e.targetRef : to.name + (to.enabled ? "" : " (disabled)");
                if (to != null) {
                    target(r, to);
                }
                rows.add(r);
            }
        }
        // deploy dependencies
        for (DepEdge e : edges) {
            if (E_DEPLOY.equals(e.kind) && (c.id.equals(e.fromId) || c.id.equals(e.toId))) {
                boolean dependsOn = c.id.equals(e.fromId);
                ItemScan other = channelById.get(dependsOn ? e.toId : e.fromId);
                DepRef r = owner(c, "Deploy dependencies", dependsOn ? "Depends on" : "Is a dependency of");
                r.message = other == null ? "(missing channel " + e.targetRef + ")" : other.name;
                if (other != null) {
                    target(r, other);
                }
                rows.add(r);
            }
        }
        // what the channel uses
        Map<String, ItemScan> fnOwner = new HashMap<String, ItemScan>();
        for (Map.Entry<String, List<ItemScan>> e : fnDefs.entrySet()) {
            ItemScan pick = e.getValue().get(0);
            for (ItemScan t : e.getValue()) {
                Lib l = t.libraryId == null ? null : libById.get(t.libraryId);
                if (l == null || l.enabledFor(c.id)) {
                    pick = t;
                    break;
                }
            }
            fnOwner.put(e.getKey(), pick);
        }
        for (int i = 0; i < c.n; i++) {
            String k = c.key[i];
            switch (c.kind[i]) {
                case ItemScan.CFG:
                    rows.add(row(c, i, "Configuration Map keys", "Reads ($cfg)"));
                    break;
                case ItemScan.LOOKUP:
                    if (configKeys.contains(k)) {
                        rows.add(row(c, i, "Configuration Map keys", "Reads ($())"));
                    } else {
                        rows.add(row(c, i, "Map variables read", "Reads ($())"));
                    }
                    break;
                case ItemScan.VELO:
                    if (configKeys.contains(k)) {
                        rows.add(row(c, i, "Configuration Map keys", "Uses (${…})"));
                    } else if (!Builtins.VELOCITY.contains(k.indexOf('.') > 0 ? k.substring(0, k.indexOf('.')) : k)) {
                        rows.add(row(c, i, "Map variables read", "Uses (${…})"));
                    }
                    break;
                case ItemScan.CALL: {
                    ItemScan t = fnOwner.get(k);
                    if (t != null) {
                        DepRef r = row(c, i, "Code template functions", "Calls " + k + "()");
                        r.message = templateLabel(t);
                        target(r, t);
                        rows.add(r);
                    }
                    break;
                }
                case ItemScan.MAP_W:
                    rows.add(row(c, i, "Map variables written", "Writes (" + ItemScan.MAPS[c.map[i]] + ")"));
                    break;
                case ItemScan.MAP_R:
                    rows.add(row(c, i, "Map variables read", "Reads (" + ItemScan.MAPS[c.map[i]] + ")"));
                    break;
                case ItemScan.MAP_SOFT:
                    rows.add(row(c, i, "Map variables read", "Used by setting"));
                    break;
                default:
                    break;
            }
        }
        List<DepRef> own = problemsOf(c);
        fillSnippets(own);
        for (DepRef p : own) {
            p.group = "Problems";
            p.relation = p.severity;
            rows.add(p);
        }
    }

    /** Index of the reference behind an edge (for its location). */
    private int edgeRef(ItemScan from, DepEdge e) {
        for (int i = 0; i < from.n; i++) {
            byte k = from.kind[i];
            boolean match = (E_WRITER.equals(e.kind) && k == ItemScan.WRITER) || (E_ROUTE.equals(e.kind) && (k == ItemScan.ROUTE_ID || k == ItemScan.ROUTE_NAME))
                    || (E_MENTION.equals(e.kind) && k == ItemScan.UUID);
            if (match && from.key[i].equals(e.targetRef) && from.locs.get(from.loc[i]).connector == e.connector) {
                return i;
            }
        }
        return -1;
    }

    private void textQuery(String key, DepResult res, List<DepRef> rows) {
        if (key.length() < 2) {
            throw new IllegalArgumentException("Enter at least 2 characters.");
        }
        String needle = key.toLowerCase();
        int total = 0;
        for (ItemScan s : all) {
            String[] texts = s.texts();
            for (int leaf = 0; leaf < texts.length && rows.size() <= MAX_ROWS; leaf++) {
                String t = texts[leaf];
                if (t == null) {
                    continue;
                }
                String lower = t.toLowerCase();
                int p = lower.indexOf(needle);
                Set<Integer> lines = new LinkedHashSet<Integer>();
                while (p >= 0 && lines.size() < 20) {
                    lines.add(JsText.lineOf(t, p));
                    p = lower.indexOf(needle, p + needle.length());
                }
                for (int ln : lines) {
                    total++;
                    ItemScan.Loc l = s.locs.get(leaf);
                    DepRef r = owner(s, ownerGroup(s), "Contains");
                    r.connector = l.connector;
                    r.connectorName = l.connectorName;
                    r.part = l.part;
                    r.step = l.step;
                    r.stepName = l.stepName;
                    r.field = l.field;
                    r.location = l.label;
                    r.line = ln;
                    r.key = key;
                    r.snippet = JsText.line(t, ln);
                    rows.add(r);
                }
            }
        }
        for (String k : configKeys) {
            if (k.toLowerCase().contains(needle)) {
                DepRef r = new DepRef();
                r.group = "Configuration Map";
                r.relation = "Key name";
                r.ownerType = T_CONFIG;
                r.ownerId = k;
                r.ownerName = k;
                r.location = "Settings › Configuration Map";
                r.key = k;
                rows.add(r);
                total++;
            }
        }
        res.subtitle = "Text found in " + total + " place" + (total == 1 ? "" : "s") + " (channels, code templates, global scripts)";
    }

    private void problemsFor(String key, String channelId, List<DepRef> rows) {
        for (DepRef p : problems) {
            if ((key != null && key.equals(p.key)) || (channelId != null && channelId.equals(p.ownerId))) {
                DepRef r = p.copy();
                r.group = "Problems";
                r.relation = p.severity;
                rows.add(r);
            }
        }
    }
}
