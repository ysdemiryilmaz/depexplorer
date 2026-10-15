package com.mirth.connect.plugins.depexplorer.server;

import java.util.concurrent.ConcurrentHashMap;

/** Shares equal names between scans (the same keys appear in many channels). */
final class Interner {

    private static final ConcurrentHashMap<String, String> MAP = new ConcurrentHashMap<String, String>();

    private Interner() {}

    static String get(String s) {
        if (s == null) {
            return null;
        }
        if (MAP.size() > 200000) {
            MAP.clear();
        }
        String e = MAP.putIfAbsent(s, s);
        return e == null ? s : e;
    }
}
