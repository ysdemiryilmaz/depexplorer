package com.mirth.connect.plugins.depexplorer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Broken references (problems) and unused items. */
public class DepReport implements Serializable {

    private static final long serialVersionUID = 1L;

    public long scannedAt;
    public List<DepRef> problems = new ArrayList<DepRef>();
    public List<DepRef> unused = new ArrayList<DepRef>();
    /** True when the lists were cut to keep the answer small. */
    public boolean truncated;

    public DepReport() {}
}
