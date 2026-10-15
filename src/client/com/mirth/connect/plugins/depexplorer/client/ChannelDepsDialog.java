package com.mirth.connect.plugins.depexplorer.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;

import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;

import com.mirth.connect.client.ui.PlatformUI;

/** "View Dependencies" for one channel, in its own window (not modal, so Mirth stays usable). */
final class ChannelDepsDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private ChannelDepsDialog(String name) {
        // no owner: the window can go behind the main window when a result is opened in the editor
        super((java.awt.Frame) null, "Dependencies – " + name);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        try {
            setIconImages(PlatformUI.MIRTH_FRAME.getIconImages());
        } catch (Exception ignore) {
            // no icon
        }
    }

    static void show(String channelId, String name) {
        ChannelDepsDialog d = new ChannelDepsDialog(name);
        ExplorerView v = new ExplorerView(true);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(v, BorderLayout.CENTER);
        Rectangle f = PlatformUI.MIRTH_FRAME.getBounds();
        d.setSize(new Dimension(Math.min(1250, Math.max(900, f.width - 120)), Math.min(820, Math.max(600, f.height - 120))));
        d.setLocationRelativeTo(PlatformUI.MIRTH_FRAME);
        d.setVisible(true);
        v.startWithChannel(channelId);
    }
}
