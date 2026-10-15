package com.mirth.connect.plugins.depexplorer.client;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JMenuItem;

import org.jdesktop.swingx.JXTaskPane;

import com.mirth.connect.client.ui.ChannelPanel;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.components.MirthTreeTable;
import com.mirth.connect.model.Channel;
import com.mirth.connect.plugins.TaskPlugin;

/**
 * Adds "View Dependencies" to the Channel Tasks on the Channels screen (and to the right-click menu of the
 * channel list). It is shown while exactly one channel is selected and opens a window with everything the
 * channel depends on and everything that depends on it.
 *
 * Mirth's ChannelPanel exposes its task pane and popup menu; the item is appended after Mirth's own tasks,
 * whose visibility Mirth sets by index, so the extra item is left alone. If that is not possible (a future
 * Mirth version), the task is shown in a small task pane of its own instead.
 */
public class DepExplorerChannelTaskPlugin extends TaskPlugin {

    static final String POINT = com.mirth.connect.plugins.depexplorer.DepExplorerConstants.CHANNEL_TASK_POINT;

    private final Action action;
    private ChannelPanel injectedInto;
    private Component taskItem;
    private JMenuItem menuItem;
    private JXTaskPane ownPane;

    public DepExplorerChannelTaskPlugin(String name) {
        super(name);
        action = new AbstractAction("View Dependencies") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                Channel c = selectedChannel();
                if (c == null) {
                    PlatformUI.MIRTH_FRAME.alertInformation(PlatformUI.MIRTH_FRAME, "Select one channel first.");
                    return;
                }
                ChannelDepsDialog.show(c.getId(), c.getName());
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, "Show what the selected channel depends on (channels, Configuration Map, code templates, map variables) and what depends on it.");
        try {
            action.putValue(Action.SMALL_ICON, new ImageIcon(com.mirth.connect.client.ui.Frame.class.getResource("images/world_link.png")));
        } catch (Exception ignore) {
            // no icon
        }
    }

    @Override
    public String getPluginPointName() {
        return POINT;
    }

    /** Puts the task into Mirth's Channel Tasks (once per ChannelPanel instance). */
    private boolean inject() {
        ChannelPanel cp = PlatformUI.MIRTH_FRAME == null ? null : PlatformUI.MIRTH_FRAME.channelPanel;
        if (cp == null) {
            return false;
        }
        if (cp == injectedInto) {
            return true;
        }
        try {
            taskItem = cp.channelTasks.add(action);
            taskItem.setFocusable(false);
            if (cp.channelPopupMenu != null) {
                menuItem = cp.channelPopupMenu.add(action);
            }
            injectedInto = cp;
            setShown(false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public JXTaskPane getTaskPane() {
        if (inject()) {
            update();
            return null;
        }
        if (ownPane == null) {
            ownPane = new JXTaskPane();
            ownPane.setTitle("Channel Dependencies");
            ownPane.setFocusable(false);
            ownPane.add(action).setFocusable(false);
        }
        update();
        return ownPane;
    }

    @Override
    public void onRowSelected(MirthTreeTable channelTable) {
        inject();
        update();
    }

    @Override
    public void onRowDeselected() {
        setShown(false);
    }

    private void update() {
        setShown(selectedChannel() != null);
    }

    private void setShown(boolean shown) {
        if (taskItem != null) {
            taskItem.setVisible(shown);
        }
        if (menuItem != null) {
            menuItem.setVisible(shown);
        }
        if (ownPane != null) {
            ownPane.setVisible(shown);
        }
    }

    /** The selected channel when exactly one channel (and no group) is selected. */
    private static Channel selectedChannel() {
        try {
            ChannelPanel cp = PlatformUI.MIRTH_FRAME.channelPanel;
            if (cp == null || cp.isGroupSelected()) {
                return null;
            }
            List<Channel> l = cp.getSelectedChannels();
            return l != null && l.size() == 1 ? l.get(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void reset() {}
}
