package tn.supcom.coldtrace.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.security.Access;
import java.util.*;
import java.net.URI;

@Path("/sites") @RequestScoped @Produces("application/json")
@RolesAllowed({Access.ADMIN, Access.QUALITY, Access.OPERATOR})
public class SiteResource {
    @Inject Catalog catalog;
    @GET public List<Map<String,Object>> list(@QueryParam("offset") @DefaultValue("0") int offset,
                                               @QueryParam("limit") @DefaultValue("200") int limit) { return catalog.list("sites", offset, limit); }
    @GET @Path("{id}") public Map<String,Object> get(@PathParam("id") String id) { return Catalog.view(catalog.require("sites", id)); }
    @POST @Consumes("application/json") @RolesAllowed(Access.ADMIN)
    public Response create(String body) {
        var d = catalog.site(null, body, true);
        return Response.created(URI.create("/api/sites/" + d.getString("_id"))).entity(Catalog.view(d)).build();
    }
    @PUT @Path("{id}") @Consumes("application/json") @RolesAllowed(Access.ADMIN)
    public Map<String,Object> update(@PathParam("id") String id, String body) { return Catalog.view(catalog.site(id, body, false)); }
    @DELETE @Path("{id}") @RolesAllowed(Access.ADMIN)
    public Map<String,Object> deactivate(@PathParam("id") String id) { return Catalog.view(catalog.deactivate("sites", id)); }
}
