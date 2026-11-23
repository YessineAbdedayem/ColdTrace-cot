package tn.supcom.coldtrace.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import tn.supcom.coldtrace.ingestion.IngestionService;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.security.Access;
import java.io.IOException;
import java.util.*;

@Path("/ingestion") @RequestScoped @Produces("application/json") @RolesAllowed(Access.ADMIN)
public class IngestionResource {
    @Inject IngestionService ingestion;
    @GET public Map<String,Object> status() throws IOException { return ingestion.status(); }
    @GET @Path("quarantine") public List<Map<String,Object>> quarantine(@QueryParam("limit") @DefaultValue("50") int limit) throws IOException {
        return ingestion.quarantined(Catalog.limit(limit));
    }
}
