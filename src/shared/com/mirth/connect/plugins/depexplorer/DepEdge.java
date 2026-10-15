package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;

/** A message flow / dependency between two channels. */
public class DepEdge implements Serializable {

    private static final long serialVersionUID = 1L;

    public String fromId;
    /** Target channel id; null when the target does not exist (then targetName / targetRef tell what was referenced). */
    public String toId;
    public String targetRef;
    public String kind;
    public int connector = -1;
    public String connectorName;
    /** false when the sending destination is disabled. */
    public boolean enabled = true;
    /** The target channel does not exist. */
    public boolean broken;
    /** For shared map variable links: the variable names. */
    public String detail;

    public DepEdge() {}
}
