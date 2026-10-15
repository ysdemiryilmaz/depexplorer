package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;

/** A "who uses it" query: type (CONFIG / CHANNEL / FUNCTION / MAPVAR / TEXT) and the key to look for. */
public class DepQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    public String type;
    public String key;
    public boolean refresh;

    public DepQuery() {}

    public DepQuery(String type, String key) {
        this.type = type;
        this.key = key;
    }
}
