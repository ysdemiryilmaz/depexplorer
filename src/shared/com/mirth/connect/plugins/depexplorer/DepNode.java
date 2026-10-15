package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** A channel in the flow graph. */
public class DepNode implements Serializable {

    private static final long serialVersionUID = 1L;

    public String id;
    public String name;
    public boolean enabled = true;
    public String sourceType;
    public String description;
    /** Destinations: "metaDataId|name|type|enabled". */
    public List<String> destinations = new ArrayList<String>();
    /** Number of problems (errors, warnings) found in the channel. */
    public int errors;
    public int warnings;

    public DepNode() {}
}
