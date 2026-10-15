package com.mirth.connect.plugins.depexplorer.client;

import java.awt.Component;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingWorker;

import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.plugins.depexplorer.DepExplorerServletInterface;

/** Server calls in the background, results on the event thread. */
final class Api {

    interface Done<T> {
        void ok(T value);
    }

    interface Busy {
        void busy(boolean on, String what);
    }

    private Api() {}

    static DepExplorerServletInterface servlet() {
        return PlatformUI.MIRTH_FRAME.mirthClient.getServlet(DepExplorerServletInterface.class);
    }

    static <T> void run(final Component owner, final Busy busy, final String what, final Callable<T> work, final Done<T> done) {
        if (busy != null) {
            busy.busy(true, what);
        }
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                if (busy != null) {
                    busy.busy(false, null);
                }
                try {
                    done.ok(get());
                } catch (ExecutionException e) {
                    Ui.showError(owner, e.getCause() == null ? e : e.getCause());
                } catch (Exception e) {
                    Ui.showError(owner, e);
                }
            }
        }.execute();
    }
}
