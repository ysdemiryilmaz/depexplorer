package com.mirth.connect.plugins.depexplorer.server;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.plugins.depexplorer.DepExplorerConstants;
import com.mirth.connect.plugins.depexplorer.DepExplorerServletInterface;
import com.mirth.connect.plugins.depexplorer.DepOverview;
import com.mirth.connect.plugins.depexplorer.DepQuery;
import com.mirth.connect.plugins.depexplorer.DepReport;
import com.mirth.connect.plugins.depexplorer.DepResult;
import com.mirth.connect.server.api.MirthServlet;

public class DepExplorerServlet extends MirthServlet implements DepExplorerServletInterface {

    private static final Logger log = LogManager.getLogger(DepExplorerServlet.class);

    public DepExplorerServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, DepExplorerConstants.PLUGIN_POINT);
    }

    /** Error without the original cause: library exceptions are not always serializable for the client. */
    private static MirthApiException api(Throwable e) {
        if (!(e instanceof IllegalArgumentException) && !(e instanceof IllegalStateException)) {
            log.warn("Dependency Explorer request failed", e);
        }
        String m = e.getMessage();
        Throwable c = e;
        while ((m == null || m.isEmpty()) && c.getCause() != null) {
            c = c.getCause();
            m = c.getMessage();
        }
        return new MirthApiException(new IllegalStateException(m == null ? e.getClass().getSimpleName() : m));
    }

    @Override
    public DepOverview getOverview(boolean refresh) {
        try {
            return DepModel.get().current(refresh).overview();
        } catch (Throwable e) {
            throw api(e);
        }
    }

    @Override
    public DepResult query(DepQuery query) {
        try {
            if (query == null) {
                throw new IllegalArgumentException("No query.");
            }
            return DepModel.get().current(query.refresh).query(query);
        } catch (Throwable e) {
            throw api(e);
        }
    }

    @Override
    public DepReport getReport() {
        try {
            return DepModel.get().current(false).report();
        } catch (Throwable e) {
            throw api(e);
        }
    }
}
