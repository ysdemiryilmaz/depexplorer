package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;

/**
 * One row of a result: a reference found somewhere (with its exact location), or a finding of the broken
 * references / unused items reports.
 */
public class DepRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Top-level group in the result tree (e.g. "Incoming", "Configuration Map"). */
    public String group;
    /** What the place does with the key: Uses, Reads, Writes, Calls, Defines, Sends to, ... */
    public String relation;
    /** The key / name the row is about (Configuration Map key, function, channel name, ...). */
    public String key;

    /** Findings only: Error / Warning / Info, a category and a readable message. */
    public String severity;
    public String category;
    public String message;

    /** Where it was found. */
    public String ownerType;
    public String ownerId;
    public String ownerName;
    /** Connector metaDataId (0 = source); -1 = not in a connector. */
    public int connector = -1;
    public String connectorName;
    /** SUMMARY / SETTINGS / FILTER / TRANSFORMER / RESPONSE / SCRIPTS / CODE. */
    public String part;
    /** Filter rule / transformer step sequence number ("3", or "2-1" inside an iterator). */
    public String step;
    public String stepName;
    /** Field or script name (e.g. "Deploy Script", "channelTemplate"). */
    public String field;
    /** Readable location, e.g. "Destination 'To LIS' › Transformer › Step 2 'Set ids'". */
    public String location;
    public int line;
    public String snippet;

    /** Another item the row points to (e.g. the target channel of a Channel Writer), for navigation. */
    public String targetType;
    public String targetId;
    public String targetName;

    public DepRef() {}

    public DepRef copy() {
        DepRef r = new DepRef();
        r.group = group;
        r.relation = relation;
        r.key = key;
        r.severity = severity;
        r.category = category;
        r.message = message;
        r.ownerType = ownerType;
        r.ownerId = ownerId;
        r.ownerName = ownerName;
        r.connector = connector;
        r.connectorName = connectorName;
        r.part = part;
        r.step = step;
        r.stepName = stepName;
        r.field = field;
        r.location = location;
        r.line = line;
        r.snippet = snippet;
        r.targetType = targetType;
        r.targetId = targetId;
        r.targetName = targetName;
        return r;
    }
}
