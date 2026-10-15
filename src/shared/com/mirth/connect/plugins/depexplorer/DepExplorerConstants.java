package com.mirth.connect.plugins.depexplorer;

public final class DepExplorerConstants {

    private DepExplorerConstants() {}

    public static final String PLUGIN_NAME = "Dependency Explorer";
    public static final String PLUGIN_POINT = "Dependency Explorer";
    public static final String CHANNEL_TASK_POINT = "Dependency Explorer Channel Task";

    public static final String PERMISSION_VIEW = "View Dependency Explorer";

    // ---- query types
    public static final String Q_CONFIG = "CONFIG";
    public static final String Q_CHANNEL = "CHANNEL";
    public static final String Q_FUNCTION = "FUNCTION";
    public static final String Q_MAPVAR = "MAPVAR";
    public static final String Q_TEXT = "TEXT";

    // ---- owner types
    public static final String T_CHANNEL = "Channel";
    public static final String T_TEMPLATE = "Code Template";
    public static final String T_GLOBAL = "Global Script";
    public static final String T_CONFIG = "Configuration Map";
    public static final String T_LIBRARY = "Code Template Library";

    // ---- location parts (where in a channel)
    public static final String P_SUMMARY = "SUMMARY";
    public static final String P_SETTINGS = "SETTINGS";
    public static final String P_FILTER = "FILTER";
    public static final String P_TRANSFORMER = "TRANSFORMER";
    public static final String P_RESPONSE = "RESPONSE";
    public static final String P_SCRIPTS = "SCRIPTS";
    public static final String P_CODE = "CODE";

    // ---- severities
    public static final String S_ERROR = "Error";
    public static final String S_WARNING = "Warning";
    public static final String S_INFO = "Info";

    // ---- flow edge kinds
    public static final String E_WRITER = "Channel Writer";
    public static final String E_ROUTE = "router.routeMessage";
    public static final String E_MENTION = "Channel id in code";
    public static final String E_DEPLOY = "Deploy dependency";
    public static final String E_GLOBALMAP = "Shared map variable";
}
