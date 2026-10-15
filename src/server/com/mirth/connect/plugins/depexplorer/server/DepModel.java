package com.mirth.connect.plugins.depexplorer.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelDependency;
import com.mirth.connect.model.ChannelMetadata;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.util.ConfigurationProperty;

/**
 * Keeps the scans of all channels, code templates and global scripts and the analysis built from them.
 *
 * Each request first checks cheaply what changed (channel revisions, code templates, libraries, global scripts,
 * Configuration Map keys, enabled flags, deploy dependencies); only changed channels are read again, so after
 * the first scan a request costs a few milliseconds. The check runs at most every few seconds.
 */
final class DepModel {

    private static final Logger log = LogManager.getLogger(DepModel.class);
    private static final DepModel INSTANCE = new DepModel();
    private static final long CHECK_MILLIS = 3000;

    static DepModel get() {
        return INSTANCE;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ItemScan> channels = new HashMap<String, ItemScan>();
    private final Map<String, ItemScan> templates = new HashMap<String, ItemScan>();
    private ItemScan globals;
    private String globalsSig;
    private String othersSig;
    private volatile Analysis analysis;
    private volatile long lastCheck;

    private DepModel() {}

    /** Drops everything (plugin stopped). */
    void clear() {
        lock.lock();
        try {
            channels.clear();
            templates.clear();
            globals = null;
            globalsSig = null;
            othersSig = null;
            analysis = null;
        } finally {
            lock.unlock();
        }
    }

    /** The current analysis, brought up to date first. */
    Analysis current(boolean force) throws Exception {
        Analysis a = analysis;
        if (!force && a != null && System.currentTimeMillis() - lastCheck < CHECK_MILLIS) {
            return a;
        }
        if (!lock.tryLock(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Dependency Explorer is busy reading the configuration. Try again in a moment.");
        }
        try {
            a = analysis;
            if (!force && a != null && System.currentTimeMillis() - lastCheck < CHECK_MILLIS) {
                return a; // another request refreshed it meanwhile
            }
            a = refresh(force);
            analysis = a;
            lastCheck = System.currentTimeMillis();
            return a;
        } finally {
            lock.unlock();
        }
    }

    private Analysis refresh(boolean force) throws Exception {
        long start = System.currentTimeMillis();
        ControllerFactory cf = ControllerFactory.getFactory();
        if (force) {
            channels.clear();
            templates.clear();
            globals = null;
        }

        // ---- channels: read again only those whose revision changed
        Map<String, Integer> revisions = cf.createChannelController().getChannelRevisions();
        Map<String, Boolean> enabled = new HashMap<String, Boolean>();
        try {
            Map<String, ChannelMetadata> md = cf.createConfigurationController().getChannelMetadata();
            if (md != null) {
                for (Map.Entry<String, ChannelMetadata> e : md.entrySet()) {
                    enabled.put(e.getKey(), e.getValue() == null || e.getValue().isEnabled());
                }
            }
        } catch (Throwable t) {
            // older / newer API: treat all as enabled
        }
        boolean changed = analysis == null || force;
        channels.keySet().retainAll(revisions.keySet());
        Set<String> toRead = new TreeSet<String>();
        for (Map.Entry<String, Integer> e : revisions.entrySet()) {
            ItemScan s = channels.get(e.getKey());
            if (s == null || e.getValue() == null || s.revision != e.getValue()) {
                toRead.add(e.getKey());
            } else {
                boolean en = !Boolean.FALSE.equals(enabled.get(e.getKey()));
                if (s.enabled != en) {
                    s.enabled = en;
                    changed = true;
                }
            }
        }
        if (channels.size() != revisions.size() - toRead.size()) {
            changed = true;
        }
        int rescanned = 0;
        if (!toRead.isEmpty()) {
            changed = true;
            Guard.check(toRead.size());
            // One channel at a time: getChannels(ids) would clone every cached channel on each call, and holding
            // all channel objects at once would need far more memory than the scans.
            Map<String, Channel> cached = cachedChannels();
            for (String id : toRead) {
                Channel c = cached == null ? null : cached.get(id);
                if (c == null || c.getRevision() == null || !c.getRevision().equals(revisions.get(id))) {
                    c = cf.createChannelController().getChannelById(id);
                }
                if (c == null) {
                    continue;
                }
                try {
                    channels.put(c.getId(), Scanner.channel(c, revisions.get(c.getId()), enabled.get(c.getId())));
                } catch (Throwable t) {
                    log.warn("Dependency Explorer could not read channel " + c.getName() + ": " + t);
                    ItemScan s = new ItemScan(com.mirth.connect.plugins.depexplorer.DepExplorerConstants.T_CHANNEL, c.getId(), c.getName());
                    s.revision = revisions.get(c.getId()) == null ? -1 : revisions.get(c.getId());
                    s.finish();
                    channels.put(c.getId(), s);
                }
                rescanned++;
            }
        }

        // ---- code templates and libraries
        List<CodeTemplateLibrary> libs = cf.createCodeTemplateController().getLibraries(null, false);
        Map<String, CodeTemplateLibrary> libOf = new HashMap<String, CodeTemplateLibrary>();
        StringBuilder libSig = new StringBuilder();
        if (libs != null) {
            for (CodeTemplateLibrary l : libs) {
                libSig.append(l.getId()).append(l.getName()).append(l.getRevision()).append(l.isIncludeNewChannels()).append(new TreeSet<String>(l.getEnabledChannelIds()))
                        .append(new TreeSet<String>(l.getDisabledChannelIds())).append(';');
                if (l.getCodeTemplates() != null) {
                    for (CodeTemplate t : l.getCodeTemplates()) {
                        libOf.put(t.getId(), l);
                        libSig.append(t.getId()).append(',');
                    }
                }
            }
        }
        List<CodeTemplate> tlist = cf.createCodeTemplateController().getCodeTemplates(null);
        Set<String> tids = new HashSet<String>();
        if (tlist != null) {
            for (CodeTemplate t : tlist) {
                tids.add(t.getId());
                ItemScan s = templates.get(t.getId());
                CodeTemplateLibrary l = libOf.get(t.getId());
                int rev = t.getRevision() == null ? 0 : t.getRevision();
                String lid = l == null ? null : l.getId();
                String lname = l == null ? null : l.getName();
                if (s == null || s.revision != rev || !eq(s.libraryId, lid) || !eq(s.libraryName, lname)) {
                    templates.put(t.getId(), Scanner.template(t, lid, lname));
                    changed = true;
                }
            }
        }
        if (templates.keySet().retainAll(tids)) {
            changed = true;
        }

        // ---- global scripts
        Map<String, String> gs = cf.createScriptController().getGlobalScripts();
        String gsig = gs == null ? "" : String.valueOf(new TreeMap<String, String>(gs).hashCode());
        if (globals == null || !gsig.equals(globalsSig)) {
            globals = Scanner.globalScripts(gs);
            globalsSig = gsig;
            changed = true;
        }

        // ---- Configuration Map keys, deploy dependencies, libraries
        Map<String, ConfigurationProperty> cm = cf.createConfigurationController().getConfigurationProperties();
        Set<String> configKeys = cm == null ? new TreeSet<String>() : new TreeSet<String>(cm.keySet());
        List<String[]> deps = new ArrayList<String[]>();
        try {
            Set<ChannelDependency> d = cf.createConfigurationController().getChannelDependencies();
            if (d != null) {
                for (ChannelDependency x : d) {
                    deps.add(new String[] { x.getDependentId(), x.getDependencyId() });
                }
            }
        } catch (Throwable t) {
            // not available
        }
        StringBuilder os = new StringBuilder();
        os.append(configKeys).append('|').append(libSig).append('|');
        for (String[] d : deps) {
            os.append(d[0]).append('>').append(d[1]).append(',');
        }
        String osig = os.toString();
        if (!osig.equals(othersSig)) {
            othersSig = osig;
            changed = true;
        }

        if (!changed && analysis != null) {
            return analysis;
        }
        List<Analysis.Lib> libraries = new ArrayList<Analysis.Lib>();
        if (libs != null) {
            for (CodeTemplateLibrary l : libs) {
                Analysis.Lib x = new Analysis.Lib();
                x.id = l.getId();
                x.name = l.getName();
                x.includeNew = l.isIncludeNewChannels();
                x.enabled = new HashSet<String>(l.getEnabledChannelIds());
                x.disabled = new HashSet<String>(l.getDisabledChannelIds());
                if (l.getCodeTemplates() != null) {
                    for (CodeTemplate t : l.getCodeTemplates()) {
                        x.templateIds.add(t.getId());
                    }
                }
                libraries.add(x);
            }
        }
        Analysis a = new Analysis(new ArrayList<ItemScan>(channels.values()), new ArrayList<ItemScan>(templates.values()), globals, configKeys, libraries,
                deps);
        a.scanMillis = System.currentTimeMillis() - start;
        a.rescanned = rescanned;
        a.cacheKb = memory() / 1024;
        if (rescanned > 0) {
            log.info("Dependency Explorer: read " + rescanned + " channel(s), analysis ready in " + a.scanMillis + " ms (" + channels.size() + " channels, "
                    + templates.size() + " code templates, about " + (memory() / 1024) + " KB kept).");
        }
        return a;
    }

    /**
     * The channel controller's own cache (read-only), so that channels need not be cloned one by one just to
     * be read. Falls back to the public API (null) if the internals differ in this Mirth version.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Channel> cachedChannels() {
        try {
            Object controller = ControllerFactory.getFactory().createChannelController();
            controller.getClass().getMethod("getChannelIds").invoke(controller); // brings the cache up to date
            java.lang.reflect.Field f = findField(controller.getClass(), "channelCache");
            f.setAccessible(true);
            Object cache = f.get(controller);
            java.lang.reflect.Field m = findField(cache.getClass(), "cacheById");
            m.setAccessible(true);
            Object map = m.get(cache);
            return map instanceof Map ? (Map<String, Channel>) map : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private long memory() {
        long m = 0;
        for (ItemScan s : channels.values()) {
            m += s.memory();
        }
        for (ItemScan s : templates.values()) {
            m += s.memory();
        }
        return m;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static <K, V> Map<K, V> linked() {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>());
    }
}
