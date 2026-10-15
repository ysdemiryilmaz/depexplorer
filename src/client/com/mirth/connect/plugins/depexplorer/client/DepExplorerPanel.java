package com.mirth.connect.plugins.depexplorer.client;

import java.awt.BorderLayout;

import com.mirth.connect.client.ui.AbstractSettingsPanel;

/** Settings › Dependency Explorer: the explorer full-screen. Nothing to save. */
public class DepExplorerPanel extends AbstractSettingsPanel {

    private static final long serialVersionUID = 1L;
    private final ExplorerView view = new ExplorerView(false);

    public DepExplorerPanel(String tabName) {
        super(tabName);
        setLayout(new BorderLayout());
        add(view, BorderLayout.CENTER);
    }

    /** Called when the tab is shown and by the "Refresh" task. */
    @Override
    public void doRefresh() {
        view.refresh(false);
    }

    @Override
    public boolean doSave() {
        return true;
    }
}
