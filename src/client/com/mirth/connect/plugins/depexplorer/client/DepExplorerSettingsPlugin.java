package com.mirth.connect.plugins.depexplorer.client;

import com.mirth.connect.client.ui.AbstractSettingsPanel;
import com.mirth.connect.plugins.SettingsPanelPlugin;
import com.mirth.connect.plugins.depexplorer.DepExplorerConstants;

/** Adds the "Dependency Explorer" tab under Settings in the Administrator. */
public class DepExplorerSettingsPlugin extends SettingsPanelPlugin {

    private DepExplorerPanel panel;

    public DepExplorerSettingsPlugin(String name) {
        super(name);
    }

    @Override
    public AbstractSettingsPanel getSettingsPanel() {
        if (panel == null) {
            panel = new DepExplorerPanel(DepExplorerConstants.PLUGIN_NAME);
        }
        return panel;
    }

    @Override
    public String getPluginPointName() {
        return DepExplorerConstants.PLUGIN_POINT;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void reset() {}
}
