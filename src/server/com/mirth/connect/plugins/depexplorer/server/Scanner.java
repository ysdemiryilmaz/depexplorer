package com.mirth.connect.plugins.depexplorer.server;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.converters.ObjectXMLSerializer;

/**
 * Reads a channel (every connector setting, filter rule, transformer step and script, each with its exact
 * place), a code template or a global script and records the references it contains.
 */
final class Scanner {

    private static final String Q = "['\"]";
    private static final String NAME = "([^'\"\\r\\n]{1,200}?)";
    private static final String MAPS = "channelMap|globalChannelMap|globalMap|connectorMap|responseMap|sourceMap";

    static final Pattern P_CFG = Pattern.compile("(?:\\$cfg|configurationMap\\.get)\\(\\s*" + Q + NAME + Q);
    static final Pattern P_LOOKUP = Pattern.compile("(?<![\\w$.])\\$\\(\\s*" + Q + NAME + Q + "\\s*\\)");
    static final Pattern P_PUT = Pattern.compile("\\b(" + MAPS + ")\\.put\\(\\s*" + Q + NAME + Q + "\\s*,");
    static final Pattern P_SHORT_PUT = Pattern.compile("(?<![\\w$.])\\$(c|co|gc|g|r)\\(\\s*" + Q + NAME + Q + "\\s*,");
    static final Pattern P_GET = Pattern.compile("\\b(" + MAPS + ")\\.(?:get|containsKey)\\(\\s*" + Q + NAME + Q);
    static final Pattern P_SHORT_GET = Pattern.compile("(?<![\\w$.])\\$(c|co|gc|g|r|s)\\(\\s*" + Q + NAME + Q + "\\s*\\)");
    static final Pattern P_VELO = Pattern.compile("\\$\\{([A-Za-z_][\\w\\-]*(?:\\.[A-Za-z_][\\w\\-]*)*)");
    static final Pattern P_ROUTE = Pattern.compile("\\brouter\\.routeMessage\\(\\s*" + Q + NAME + Q);
    static final Pattern P_ROUTE_ID = Pattern.compile("\\brouter\\.routeMessageByChannelId\\(\\s*" + Q + NAME + Q);
    static final Pattern P_UUID = Pattern.compile("(?<![0-9a-fA-F-])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-fA-F-])");
    static final Pattern P_DEF = Pattern.compile("\\bfunction\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    static final Pattern P_FN_PARAMS = Pattern.compile("\\bfunction\\b\\s*[A-Za-z_$]?[\\w$]*\\s*\\(([^)]*)\\)");
    static final Pattern P_VAR = Pattern.compile("\\b(?:var|let|const)\\s+([A-Za-z_$][\\w$]*)");
    static final Pattern P_VAR_LIST = Pattern.compile("\\b(?:var|let|const)\\s+([^;\\n]*)");
    static final Pattern P_ASSIGN_FN = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*[:=]\\s*function\\b");
    static final Pattern P_CATCH = Pattern.compile("\\bcatch\\s*\\(\\s*([A-Za-z_$][\\w$]*)");
    static final Pattern P_ARROW = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=>|\\(([^()]*)\\)\\s*=>");
    static final Pattern P_MORE_VARS = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*(?:=|,|$)");
    static final Pattern P_CALL = Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*\\(");
    static final Pattern P_IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList("if", "for", "while", "switch", "catch", "function", "return", "typeof", "with",
            "do", "else", "new", "delete", "in", "of", "instanceof", "void", "throw", "case", "yield", "await", "try", "finally", "var", "let", "const",
            "super", "this", "each", "get", "set", "async"));

    /** Fields of filter rules / transformer steps that hold JavaScript (expressions). */
    private static final Set<String> JS_ELEMENT_FIELDS = new HashSet<String>(Arrays.asList("script", "mapping", "defaultValue", "field", "messageSegment",
            "target", "string"));

    private static final String[][] CHANNEL_SCRIPTS = { { "preprocessingScript", "Preprocessor Script", "CHANNEL_PREPROCESSOR" },
            { "postprocessingScript", "Postprocessor Script", "CHANNEL_POSTPROCESSOR" }, { "deployScript", "Deploy Script", "CHANNEL_DEPLOY" },
            { "undeployScript", "Undeploy Script", "CHANNEL_UNDEPLOY" } };

    private Scanner() {}

    // =====================================================================
    // Channel
    // =====================================================================

    static ItemScan channel(Channel c, Integer revision, Boolean enabled) throws Exception {
        ItemScan s = new ItemScan(T_CHANNEL, c.getId(), c.getName());
        s.revision = revision != null ? revision : (c.getRevision() == null ? 0 : c.getRevision());
        s.enabled = enabled == null || enabled;
        s.description = c.getDescription();
        Element root = parse(ObjectXMLSerializer.getInstance().serialize(c));
        for (Element k : children(root)) {
            String tag = k.getTagName();
            if (tag.equals("sourceConnector")) {
                connector(s, k, true);
            } else if (tag.equals("destinationConnectors")) {
                for (Element d : children(k)) {
                    connector(s, d, false);
                }
            } else if (tag.equals("properties")) {
                channelProperties(s, k);
            } else {
                for (String[] sc : CHANNEL_SCRIPTS) {
                    if (sc[0].equals(tag)) {
                        ItemScan.Loc l = new ItemScan.Loc();
                        l.part = P_SCRIPTS;
                        l.field = sc[1];
                        l.label = "Scripts › " + sc[1];
                        l.context = sc[2];
                        l.js = true;
                        leaf(s, l, text(k));
                    }
                }
            }
            // exportData (tags, metadata, dependencies), ids, names, description: not references
        }
        s.finish();
        return s;
    }

    private static void channelProperties(ItemScan s, Element props) {
        Element cols = child(props, "metaDataColumns");
        if (cols != null) {
            for (Element col : children(cols)) {
                String mapping = text(child(col, "mappingName"));
                if (!mapping.isEmpty()) {
                    ItemScan.Loc l = new ItemScan.Loc();
                    l.part = P_SUMMARY;
                    l.field = "Custom Metadata";
                    l.label = "Summary › Custom Metadata › " + text(child(col, "name"));
                    int leaf = s.addLeaf(l, mapping);
                    s.add(ItemScan.MAP_SOFT, ItemScan.M_ANY, mapping, leaf, 0);
                }
            }
        }
        Element att = child(props, "attachmentProperties");
        if (att != null) {
            String type = text(child(att, "type"));
            boolean js = type.toLowerCase().contains("javascript");
            List<String[]> leaves = new ArrayList<String[]>();
            collect(child(att, "properties"), "", leaves);
            for (String[] lf : leaves) {
                ItemScan.Loc l = new ItemScan.Loc();
                l.part = P_SUMMARY;
                l.field = "Attachment " + (js ? "Script" : "Settings");
                l.label = "Summary › Attachment (" + type + ")";
                l.context = js ? "CHANNEL_ATTACHMENT" : null;
                l.js = js;
                leaf(s, l, lf[1]);
            }
        }
    }

    private static void connector(ItemScan s, Element e, boolean source) {
        int mdid = parseInt(text(child(e, "metaDataId")), source ? 0 : -1);
        String name = source ? "Source" : text(child(e, "name"));
        String transport = text(child(e, "transportName"));
        boolean enabled = !"false".equals(text(child(e, "enabled")));
        String base = source ? "Source" : "Destination '" + name + "'";
        if (source) {
            s.sourceType = transport;
        } else {
            s.destinations.add(new String[] { String.valueOf(mdid), name, transport, String.valueOf(enabled) });
        }

        Element props = child(e, "properties");
        if (props != null) {
            boolean dbScript = "true".equals(text(child(props, "useScript")));
            List<String[]> leaves = new ArrayList<String[]>();
            collect(props, "", leaves);
            for (String[] lf : leaves) {
                String path = lf[0];
                String field = path.substring(path.lastIndexOf('/') + 1);
                if (path.contains("resourceIds/")) {
                    continue;
                }
                ItemScan.Loc l = loc(mdid, name, enabled, P_SETTINGS, base + " › Settings › " + readable(field));
                l.field = field;
                l.context = source ? "SOURCE_RECEIVER" : "DESTINATION_DISPATCHER";
                l.js = field.equals("script") || field.endsWith("Script") || (dbScript && (field.equals("query") || field.equals("select") || field.equals("update")));
                String value = lf[1];
                if (field.equals("channelId") && transport.equals("Channel Writer")) {
                    int leaf = s.addLeaf(l, value);
                    if (!value.trim().isEmpty() && !value.trim().equalsIgnoreCase("none")) {
                        if (value.contains("${")) {
                            references(s, leaf, value, false);
                        } else {
                            s.add(ItemScan.WRITER, (byte) 0, value.trim(), leaf, 0);
                        }
                    }
                    continue;
                }
                if (path.contains("mapVariables/")) {
                    l.label = base + " › Settings › Map Variables";
                    int leaf = s.addLeaf(l, value);
                    s.add(ItemScan.MAP_SOFT, ItemScan.M_ANY, value.trim(), leaf, 0);
                    continue;
                }
                if (field.equals("responseVariable") || field.equals("threadAssignmentVariable")) {
                    String v = value.trim();
                    int leaf = s.addLeaf(l, value);
                    // "None", "Auto-generate (...)", "Postprocessor" and destination responses ("d1") are not map variables
                    if (!v.isEmpty() && !v.equals("None") && !v.startsWith("Auto-generate") && !v.equals("Postprocessor") && !v.matches("d\\d+")) {
                        s.add(ItemScan.MAP_SOFT, ItemScan.M_ANY, v, leaf, 0);
                    }
                    continue;
                }
                leaf(s, l, value);
            }
        }

        Element filter = child(e, "filter");
        if (filter != null) {
            elements(s, child(filter, "elements"), mdid, name, enabled, P_FILTER, base + " › Filter", "Rule",
                    source ? "SOURCE_FILTER_TRANSFORMER" : "DESTINATION_FILTER_TRANSFORMER");
        }
        Element tr = child(e, "transformer");
        if (tr != null) {
            elements(s, child(tr, "elements"), mdid, name, enabled, P_TRANSFORMER, base + " › Transformer", "Step",
                    source ? "SOURCE_FILTER_TRANSFORMER" : "DESTINATION_FILTER_TRANSFORMER");
            if (source) {
                // Batch script of the inbound data type (Source › Set Data Types › Batch)
                for (String which : new String[] { "inboundProperties" }) {
                    Element p = child(tr, which);
                    Element bp = p == null ? null : child(p, "batchProperties");
                    Element bs = bp == null ? null : child(bp, "batchScript");
                    if (bs != null && !text(bs).trim().isEmpty()) {
                        ItemScan.Loc l = loc(mdid, name, enabled, P_SETTINGS, base + " › Data Types › Batch Script");
                        l.field = "Batch Script";
                        l.context = "CHANNEL_BATCH";
                        l.js = true;
                        leaf(s, l, text(bs));
                    }
                }
            }
        }
        Element rt = child(e, "responseTransformer");
        if (rt != null) {
            elements(s, child(rt, "elements"), mdid, name, enabled, P_RESPONSE, base + " › Response Transformer", "Step", "DESTINATION_RESPONSE_TRANSFORMER");
        }
    }

    private static void elements(ItemScan s, Element list, int mdid, String cname, boolean cEnabled, String part, String base, String noun, String context) {
        if (list == null) {
            return;
        }
        for (Element el : children(list)) {
            String cls = el.getTagName();
            String type = cls.substring(cls.lastIndexOf('.') + 1);
            String stepName = text(child(el, "name"));
            String seq = text(child(el, "sequenceNumber"));
            boolean enabled = !"false".equals(text(child(el, "enabled")));
            String label = base + " › " + noun + " " + seq + (stepName.isEmpty() ? "" : " '" + stepName + "'");
            if (type.startsWith("Iterator")) {
                Element p = child(el, "properties");
                if (p != null) {
                    String target = text(child(p, "target"));
                    if (!target.isEmpty()) {
                        ItemScan.Loc l = loc(mdid, cname, cEnabled, part, label + " › target");
                        step(l, seq, stepName, enabled, "target", context, true);
                        leaf(s, l, target);
                    }
                    elements(s, child(p, "children"), mdid, cname, cEnabled, part, base, noun, context);
                }
                continue;
            }
            String scope = text(child(el, "scope"));
            List<String[]> leaves = new ArrayList<String[]>();
            collect(el, "", leaves);
            for (String[] lf : leaves) {
                String path = lf[0];
                String field = path.substring(path.lastIndexOf('/') + 1);
                if (path.equals("name") || path.equals("sequenceNumber") || path.equals("enabled") || path.equals("scope") || path.equals("operator")
                        || path.equals("condition")) {
                    continue;
                }
                ItemScan.Loc l = loc(mdid, cname, cEnabled, part, label);
                step(l, seq, stepName, enabled, field, context, JS_ELEMENT_FIELDS.contains(field));
                if (type.equals("MapperStep") && path.equals("variable")) {
                    int leaf = s.addLeaf(l, lf[1]);
                    s.add(ItemScan.MAP_W, mapOfScope(scope), lf[1].trim(), leaf, 0);
                    continue;
                }
                leaf(s, l, lf[1]);
            }
        }
    }

    private static void step(ItemScan.Loc l, String seq, String name, boolean enabled, String field, String context, boolean js) {
        l.step = seq;
        l.stepName = name;
        l.stepEnabled = enabled;
        l.field = field;
        l.context = context;
        l.js = js;
    }

    private static byte mapOfScope(String scope) {
        if ("GLOBAL_CHANNEL".equals(scope)) {
            return ItemScan.M_GLOBAL_CHANNEL;
        } else if ("GLOBAL".equals(scope)) {
            return ItemScan.M_GLOBAL;
        } else if ("RESPONSE".equals(scope)) {
            return ItemScan.M_RESPONSE;
        } else if ("CONNECTOR".equals(scope)) {
            return ItemScan.M_CONNECTOR;
        }
        return ItemScan.M_CHANNEL;
    }

    private static ItemScan.Loc loc(int mdid, String cname, boolean enabled, String part, String label) {
        ItemScan.Loc l = new ItemScan.Loc();
        l.connector = mdid;
        l.connectorName = cname;
        l.connectorEnabled = enabled;
        l.part = part;
        l.label = label;
        return l;
    }

    // =====================================================================
    // Code templates and global scripts
    // =====================================================================

    static ItemScan template(CodeTemplate t, String libraryId, String libraryName) {
        ItemScan s = new ItemScan(T_TEMPLATE, t.getId(), t.getName());
        s.revision = t.getRevision() == null ? 0 : t.getRevision();
        s.libraryId = libraryId;
        s.libraryName = libraryName;
        try {
            s.addToScripts = t.isAddToScripts();
        } catch (Throwable ignore) {
            s.addToScripts = true;
        }
        try {
            if (t.getContextSet() != null) {
                s.contexts = new HashSet<String>();
                for (Object c : t.getContextSet()) {
                    s.contexts.add(String.valueOf(((Enum<?>) c).name()));
                }
            }
        } catch (Throwable ignore) {
            s.contexts = null;
        }
        String code = t.getCode();
        ItemScan.Loc l = new ItemScan.Loc();
        l.part = P_CODE;
        l.field = "Code";
        l.label = (libraryName == null ? "" : libraryName + " › ") + t.getName();
        l.js = true;
        int leaf = leaf(s, l, code == null ? "" : code);
        if (leaf >= 0) {
            for (int i = 0; i < s.n; i++) {
                if (s.kind[i] == ItemScan.DEF && !s.functions.contains(s.key[i])) {
                    s.functions.add(s.key[i]);
                }
            }
        }
        s.finish();
        return s;
    }

    static ItemScan globalScripts(Map<String, String> scripts) {
        ItemScan s = new ItemScan(T_GLOBAL, "globalScripts", "Global Scripts");
        if (scripts != null) {
            for (Map.Entry<String, String> e : scripts.entrySet()) {
                ItemScan.Loc l = new ItemScan.Loc();
                l.part = P_SCRIPTS;
                l.field = e.getKey();
                l.label = "Global Scripts › " + e.getKey();
                l.context = "GLOBAL_" + e.getKey().toUpperCase();
                l.js = true;
                leaf(s, l, e.getValue() == null ? "" : e.getValue());
            }
        }
        s.finish();
        return s;
    }

    // =====================================================================
    // References in one text
    // =====================================================================

    /** Setting values that can contain no reference and are not worth a text search (flags, numbers). */
    private static final Pattern TRIVIAL = Pattern.compile("true|false|TRUE|FALSE|-?\\d+(?:\\.\\d+)?|None|none|null|UTF-8|[A-Z][A-Z0-9_]{1,30}");

    private static int leaf(ItemScan s, ItemScan.Loc l, String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }
        if (!l.js && text.length() < 25 && TRIVIAL.matcher(text.trim()).matches()) {
            return -1;
        }
        int leaf = s.addLeaf(l, text);
        references(s, leaf, text, l.js);
        return leaf;
    }

    static void references(ItemScan s, int leaf, String text, boolean js) {
        String code = js ? JsText.blank(text, false) : text; // without comments
        Lines lines = new Lines(code);
        if (code.contains("cfg(") || code.contains("configurationMap")) {
            find(s, leaf, code, lines, P_CFG, ItemScan.CFG, ItemScan.M_CONFIG);
        }
        boolean dollar = code.indexOf('$') >= 0;
        if (dollar && code.contains("$(")) {
            find(s, leaf, code, lines, P_LOOKUP, ItemScan.LOOKUP, ItemScan.M_ANY);
        }
        Matcher m;
        if (code.contains(".put(")) {
            m = P_PUT.matcher(code);
            while (m.find()) {
                s.add(ItemScan.MAP_W, mapOf(m.group(1)), m.group(2), leaf, lines.of(m.start()));
            }
        }
        if (dollar) {
            m = P_SHORT_PUT.matcher(code);
            while (m.find()) {
                s.add(ItemScan.MAP_W, mapOfShort(m.group(1)), m.group(2), leaf, lines.of(m.start()));
            }
            m = P_SHORT_GET.matcher(code);
            while (m.find()) {
                s.add(ItemScan.MAP_R, mapOfShort(m.group(1)), m.group(2), leaf, lines.of(m.start()));
            }
        }
        if (code.contains(".get(") || code.contains(".containsKey(")) {
            m = P_GET.matcher(code);
            while (m.find()) {
                s.add(ItemScan.MAP_R, mapOf(m.group(1)), m.group(2), leaf, lines.of(m.start()));
            }
        }
        if (!js && dollar && code.contains("${")) {
            find(s, leaf, code, lines, P_VELO, ItemScan.VELO, ItemScan.M_ANY);
        }
        if (code.contains("router.")) {
            find(s, leaf, code, lines, P_ROUTE, ItemScan.ROUTE_NAME, (byte) 0);
            find(s, leaf, code, lines, P_ROUTE_ID, ItemScan.ROUTE_ID, (byte) 0);
        }
        uuids(s, leaf, code, lines);
        if (js) {
            functions(s, leaf, JsText.blank(text, true), lines);
        }
    }

    /** Channel-id-like UUIDs (8-4-4-4-12 lower-case hex), found without a regex scan of every character. */
    private static void uuids(ItemScan s, int leaf, String code, Lines lines) {
        int from = 0;
        int len = code.length();
        while (true) {
            int d = code.indexOf('-', from);
            if (d < 0) {
                return;
            }
            from = d + 1;
            // a UUID's first dash is at offset 8
            int st = d - 8;
            if (st < 0 || st + 36 > len) {
                continue;
            }
            if (st > 0 && isUuidChar(code.charAt(st - 1))) {
                continue;
            }
            if (st + 36 < len && isUuidChar(code.charAt(st + 36))) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < 36 && ok; i++) {
                char c = code.charAt(st + i);
                ok = (i == 8 || i == 13 || i == 18 || i == 23) ? c == '-' : (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            }
            if (ok) {
                s.add(ItemScan.UUID, (byte) 0, code.substring(st, st + 36), leaf, lines.of(st));
                from = st + 36;
            }
        }
    }

    private static boolean isUuidChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '-';
    }

    private static void functions(ItemScan s, int leaf, String code, Lines lines) {
        Matcher m;
        if (code.contains("function")) {
            m = P_DEF.matcher(code);
            while (m.find()) {
                s.add(ItemScan.DEF, (byte) 0, m.group(1), leaf, lines.of(m.start()));
                s.localNames.add(m.group(1));
            }
            m = P_FN_PARAMS.matcher(code);
            while (m.find()) {
                names(m.group(1), s.localNames);
            }
            m = P_ASSIGN_FN.matcher(code);
            while (m.find()) {
                s.localNames.add(m.group(1));
            }
        }
        if (code.contains("var ") || code.contains("let ") || code.contains("const ")) {
            m = P_VAR_LIST.matcher(code);
            while (m.find()) {
                // var a = 1, b, c = function() ... : the first name and every name after a comma
                String decl = m.group(1);
                Matcher first = P_IDENT.matcher(decl);
                if (first.lookingAt()) {
                    s.localNames.add(first.group());
                }
                if (decl.indexOf(',') >= 0) {
                    Matcher more = P_MORE_VARS.matcher(decl);
                    while (more.find()) {
                        s.localNames.add(more.group(1));
                    }
                }
            }
        }
        if (code.contains("catch")) {
            m = P_CATCH.matcher(code);
            while (m.find()) {
                s.localNames.add(m.group(1));
            }
        }
        if (code.contains("=>")) {
            m = P_ARROW.matcher(code);
            while (m.find()) {
                names(m.group(1) != null ? m.group(1) : m.group(2), s.localNames);
            }
        }
        // calls: identifier (not after '.') followed by '(' - a linear scan from each '('
        for (int p = code.indexOf('('); p >= 0; p = code.indexOf('(', p + 1)) {
            int e = p - 1;
            while (e >= 0 && (code.charAt(e) == ' ' || code.charAt(e) == '\t')) {
                e--;
            }
            int b = e;
            while (b >= 0 && isIdent(code.charAt(b))) {
                b--;
            }
            if (b == e) {
                continue; // no identifier before '('
            }
            char first = code.charAt(b + 1);
            if (Character.isDigit(first)) {
                continue;
            }
            if (b >= 0 && code.charAt(b) == '.') {
                continue; // method call
            }
            String f = code.substring(b + 1, e + 1);
            if (KEYWORDS.contains(f)) {
                continue;
            }
            int q = b;
            while (q >= 0 && Character.isWhitespace(code.charAt(q))) {
                q--;
            }
            if (q >= 0 && code.charAt(q) == '.') {
                continue; // "obj. method(" with a line break
            }
            int w = q;
            while (w >= 0 && Character.isLetter(code.charAt(w))) {
                w--;
            }
            String prev = code.substring(w + 1, q + 1);
            if (prev.equals("function") || prev.equals("new")) {
                continue;
            }
            s.add(ItemScan.CALL, (byte) 0, f, leaf, lines.of(p));
        }
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Line numbers by binary search over the line starts. */
    static final class Lines {
        private int[] starts = new int[16];
        private int count;

        Lines(String s) {
            starts[count++] = 0;
            for (int i = s.indexOf('\n'); i >= 0; i = s.indexOf('\n', i + 1)) {
                if (count == starts.length) {
                    starts = java.util.Arrays.copyOf(starts, count * 2);
                }
                starts[count++] = i + 1;
            }
        }

        int of(int pos) {
            if (count == 1) {
                return 0; // a one-line value (a setting): a line number would only be noise
            }
            int i = java.util.Arrays.binarySearch(starts, 0, count, pos);
            return (i >= 0 ? i : -i - 2) + 1;
        }
    }

    private static void names(String list, Set<String> out) {
        if (list == null) {
            return;
        }
        Matcher m = P_IDENT.matcher(list.replaceAll("=[^,]*", ""));
        while (m.find()) {
            out.add(m.group());
        }
    }

    private static void find(ItemScan s, int leaf, String text, Lines lines, Pattern p, byte kind, byte map) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            s.add(kind, map, m.group(1).trim(), leaf, lines.of(m.start()));
        }
    }

    static byte mapOf(String name) {
        if (name.equals("channelMap")) {
            return ItemScan.M_CHANNEL;
        } else if (name.equals("globalChannelMap")) {
            return ItemScan.M_GLOBAL_CHANNEL;
        } else if (name.equals("globalMap")) {
            return ItemScan.M_GLOBAL;
        } else if (name.equals("connectorMap")) {
            return ItemScan.M_CONNECTOR;
        } else if (name.equals("responseMap")) {
            return ItemScan.M_RESPONSE;
        } else if (name.equals("sourceMap")) {
            return ItemScan.M_SOURCE;
        }
        return ItemScan.M_ANY;
    }

    static byte mapOfShort(String s) {
        if (s.equals("c")) {
            return ItemScan.M_CHANNEL;
        } else if (s.equals("co")) {
            return ItemScan.M_CONNECTOR;
        } else if (s.equals("gc")) {
            return ItemScan.M_GLOBAL_CHANNEL;
        } else if (s.equals("g")) {
            return ItemScan.M_GLOBAL;
        } else if (s.equals("r")) {
            return ItemScan.M_RESPONSE;
        } else if (s.equals("s")) {
            return ItemScan.M_SOURCE;
        }
        return ItemScan.M_ANY;
    }

    // =====================================================================
    // XML helpers
    // =====================================================================

    /** Collects every text leaf under e as [relative path, text]. */
    private static void collect(Element e, String path, List<String[]> out) {
        if (e == null) {
            return;
        }
        List<Element> kids = children(e);
        if (kids.isEmpty()) {
            String t = e.getTextContent();
            if (t != null && !t.trim().isEmpty()) {
                out.add(new String[] { path.isEmpty() ? e.getTagName() : path, t });
            }
            return;
        }
        for (Element k : kids) {
            collect(k, path.isEmpty() ? k.getTagName() : path + "/" + k.getTagName(), out);
        }
    }

    static Element parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    static List<Element> children(Element e) {
        List<Element> l = new ArrayList<Element>();
        if (e == null) {
            return l;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                l.add((Element) n);
            }
        }
        return l;
    }

    static Element child(Element e, String tag) {
        if (e == null) {
            return null;
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) {
                return (Element) n;
            }
        }
        return null;
    }

    static String text(Element e) {
        return e == null ? "" : e.getTextContent();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** "channelTemplate" -> "Channel Template" */
    static String readable(String field) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else {
                if (Character.isUpperCase(c) && !Character.isUpperCase(field.charAt(i - 1))) {
                    sb.append(' ');
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
