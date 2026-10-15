package com.mirth.connect.plugins.depexplorer;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

/** REST contract between the Administrator and the server side: /api/extensions/depexplorer/... */
@Path("/extensions/depexplorer")
@Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
public interface DepExplorerServletInterface extends BaseServletInterface {

    String PERMISSION_VIEW = DepExplorerConstants.PERMISSION_VIEW;

    @GET
    @Path("/overview")
    @MirthOperation(name = "depExplorerOverview", display = "Dependency Explorer: channel graph and summary", permission = PERMISSION_VIEW, auditable = false)
    DepOverview getOverview(@Param("refresh") @QueryParam("refresh") boolean refresh) throws ClientException;

    @POST
    @Path("/_query")
    @MirthOperation(name = "depExplorerQuery", display = "Dependency Explorer: who uses it", permission = PERMISSION_VIEW, auditable = false)
    DepResult query(@Param("query") DepQuery query) throws ClientException;

    @GET
    @Path("/report")
    @MirthOperation(name = "depExplorerReport", display = "Dependency Explorer: broken references and unused items", permission = PERMISSION_VIEW, auditable = false)
    DepReport getReport() throws ClientException;
}
