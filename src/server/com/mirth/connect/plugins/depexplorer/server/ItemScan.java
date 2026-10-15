package com.mirth.connect.plugins.depexplorer.server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * What was found in one item (channel, code template or global script): its places ("leaves": a script, a
 * connector setting, a transformer step field ...) and the references in them.
 *
 * Kept small on purpose, because the scan of every channel stays in memory between requests: references are
 * stored column-wise (no object per reference), names are interned, and the leaf texts (needed only to show a
 * code line) are kept compressed.
 */
final class ItemScan {

    // ---- reference kinds
    static final byte CFG = 1; // $cfg('k') / configurationMap.get('k')
    static final byte LOOKUP = 2; // $('k') - Configuration Map or any map
    static final byte VELO = 3; // ${k} in a connector setting (Velocity)
    static final byte MAP_W = 4; // write to a map
    static final byte MAP_R = 5; // explicit read from a map
    static final byte MAP_SOFT = 6; // name used by a setting (metadata column, response variable, Channel Writer map variables ...)
    static final byte CALL = 7; // fn(...)
    static final byte DEF = 8; // function fn(...)
    static final byte WRITER = 9; // Channel Writer target channel id
    static final byte ROUTE_NAME = 10; // router.routeMessage('name', ...)
    static final byte ROUTE_ID = 11; // router.routeMessageByChannelId('id', ...)
    static final byte UUID = 12; // a channel-id-like UUID in a script / setting

    // ---- maps
    static final String[] MAPS = { "channelMap", "globalChannelMap", "globalMap", "connectorMap", "responseMap", "sourceMap", "any map", "configurationMap" };
    static final byte M_CHANNEL = 0, M_GLOBAL_CHANNEL = 1, M_GLOBAL = 2, M_CONNECTOR = 3, M_RESPONSE = 4, M_SOURCE = 5, M_ANY = 6, M_CONFIG = 7;

    /** A place in the item. */
    static final class Loc {
        int connector = -1;
        String connectorName;
        boolean connectorEnabled = true;
        String part;
        String step;
        String stepName;
        boolean stepEnabled = true;
        String field;
        String label;
        /** Code template context (ContextType name) of a script, or null. */
        String context;
        boolean js;
    }

    final String type;
    final String id;
    String name;
    int revision;

    // channel
    boolean enabled = true;
    String sourceType;
    String description;
    /** metaDataId, name, transport, enabled */
    final List<String[]> destinations = new ArrayList<String[]>();

    // code template
    String libraryId;
    String libraryName;
    boolean addToScripts = true;
    Set<String> contexts;
    final List<String> functions = new ArrayList<String>();

    /** Function / variable names declared inside this item (used to avoid flagging local functions as unknown). */
    final Set<String> localNames = new HashSet<String>();

    final List<Loc> locs = new ArrayList<Loc>();
    private List<String> pendingTexts = new ArrayList<String>();
    private byte[] packedTexts;
    private int packedLength;

    // references, column-wise
    int n;
    byte[] kind = new byte[16];
    byte[] map = new byte[16];
    String[] key = new String[16];
    int[] loc = new int[16];
    int[] line = new int[16];
    private final Set<String> seen = new HashSet<String>();

    ItemScan(String type, String id, String name) {
        this.type = type;
        this.id = id;
        this.name = name;
    }

    int addLeaf(Loc l, String text) {
        l.label = Interner.get(l.label);
        l.connectorName = Interner.get(l.connectorName);
        l.field = Interner.get(l.field);
        l.stepName = Interner.get(l.stepName);
        locs.add(l);
        pendingTexts.add(text);
        return locs.size() - 1;
    }

    void add(byte k, byte m, String name, int leaf, int ln) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!seen.add(k + "|" + m + "|" + leaf + "|" + name)) {
            return; // one row per place and name is enough
        }
        if (n == kind.length) {
            int c = n * 2;
            kind = Arrays.copyOf(kind, c);
            map = Arrays.copyOf(map, c);
            key = Arrays.copyOf(key, c);
            loc = Arrays.copyOf(loc, c);
            line = Arrays.copyOf(line, c);
        }
        kind[n] = k;
        map[n] = m;
        key[n] = Interner.get(name);
        loc[n] = leaf;
        line[n] = ln;
        n++;
    }

    /** Ends the scan: compresses the texts and trims the arrays. */
    void finish() {
        seen.clear();
        kind = Arrays.copyOf(kind, n);
        map = Arrays.copyOf(map, n);
        key = Arrays.copyOf(key, n);
        loc = Arrays.copyOf(loc, n);
        line = Arrays.copyOf(line, n);
        StringBuilder sb = new StringBuilder();
        for (String t : pendingTexts) {
            sb.append(t.replace('\u0000', ' ')).append('\u0000');
        }
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        packedLength = raw.length;
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int c = d.deflate(buf);
            bo.write(buf, 0, c);
        }
        d.end();
        packedTexts = bo.toByteArray();
        pendingTexts = null;
    }

    /** The leaf texts (unpacked on demand). */
    String[] texts() {
        if (pendingTexts != null) {
            return pendingTexts.toArray(new String[0]);
        }
        try {
            Inflater inf = new Inflater();
            inf.setInput(packedTexts);
            byte[] raw = new byte[packedLength];
            int off = 0;
            while (off < raw.length && !inf.finished()) {
                int c = inf.inflate(raw, off, raw.length - off);
                if (c == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                off += c;
            }
            inf.end();
            String all = new String(raw, 0, off, StandardCharsets.UTF_8);
            String[] parts = all.split("\u0000", -1);
            return parts.length > locs.size() ? Arrays.copyOf(parts, locs.size()) : parts;
        } catch (DataFormatException e) {
            return new String[locs.size()];
        }
    }

    /** Rough heap use of this scan in bytes (labels are shared between scans, so counted once per scan at most). */
    long memory() {
        return 96 + (packedTexts == null ? 0 : packedTexts.length + 16) + n * 18L + locs.size() * 56L;
    }
}
