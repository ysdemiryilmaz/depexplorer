package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Everything the explorer needs to start: the channel graph, suggestion lists and counts. */
public class DepOverview implements Serializable {

    private static final long serialVersionUID = 1L;

    public long scannedAt;
    public long scanMillis;
    /** How many channels had to be read again in the last scan (the rest came from the cache). */
    public int rescanned;
    /** Approximate memory the server keeps for the scans, in KB. */
    public long cacheKb;

    public List<DepNode> channels = new ArrayList<DepNode>();
    public List<DepEdge> edges = new ArrayList<DepEdge>();

    public List<String> configKeys = new ArrayList<String>();
    /** Keys read with $cfg / configurationMap.get that are not defined in the Configuration Map. */
    public List<String> missingConfigKeys = new ArrayList<String>();
    /** "function|template|library". */
    public List<String> functions = new ArrayList<String>();
    /** "name|maps" (maps comma separated). */
    public List<String> mapVariables = new ArrayList<String>();
    /** "id|name|library". */
    public List<String> templates = new ArrayList<String>();
    public List<String> globalScripts = new ArrayList<String>();

    public int errors;
    public int warnings;
    public int unused;

    public DepOverview() {}
}
