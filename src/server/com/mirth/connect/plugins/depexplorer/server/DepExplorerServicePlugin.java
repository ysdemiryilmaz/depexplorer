package com.mirth.connect.plugins.depexplorer.server;

import static com.mirth.connect.plugins.depexplorer.DepExplorerConstants.*;

import java.util.Map;
import java.util.Properties;

import com.mirth.connect.client.core.TaskConstants;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.plugins.depexplorer.DepExplorerServletInterface;

public class DepExplorerServicePlugin implements ServicePlugin {

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT;
    }

    @Override
    public void init(Properties properties) {}

    @Override
    public void update(Properties properties) {}

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] { new ExtensionPermission(PLUGIN_POINT, PERMISSION_VIEW,
                "View the Dependency Explorer: who uses what, the channel flow graph, broken references and unused items.",
                OperationUtil.getOperationNamesForPermission(PERMISSION_VIEW, DepExplorerServletInterface.class), new String[] { TaskConstants.SETTINGS_REFRESH }) };
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return null;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        DepModel.get().clear();
    }
}
