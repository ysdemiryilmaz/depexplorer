package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Answer to a query. */
public class DepResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public String type;
    public String key;
    /** Resolved display name (e.g. channel name for a channel id). */
    public String title;
    public String subtitle;
    public List<DepRef> rows = new ArrayList<DepRef>();
    public boolean truncated;

    public DepResult() {}
}
