package tn.supcom.coldtrace.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.security.Access;
import java.util.*;

@Path("/thresholds") @RequestScoped @Produces("application/json")
@RolesAllowed({Access.ADMIN, Access.QUALITY, Access.OPERATOR})
public class ThresholdResource {
    @Inject Catalog catalog;
    @GET public List<Map<String,Object>> list(@QueryParam("offset") @DefaultValue("0") int offset,
                                               @QueryParam("limit") @DefaultValue("200") int limit) { return catalog.list("thresholds", offset, limit); }
    @GET @Path("{profile}") public Map<String,Object> get(@PathParam("profile") String profile) { return Catalog.view(catalog.require("thresholds", profile)); }
    @PUT @Path("{profile}") @Consumes("application/json") @RolesAllowed(Access.ADMIN)
    public Map<String,Object> update(@PathParam("profile") String profile, String body) { return Catalog.view(catalog.threshold(profile, body)); }
}
