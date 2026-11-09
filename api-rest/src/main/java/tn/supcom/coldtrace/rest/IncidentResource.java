package tn.supcom.coldtrace.rest;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.conversions.Bson;
import tn.supcom.coldtrace.model.Incident;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GET /api/incidents?status=open|closed&limit=50 : newest first. Without status, both. */
@Path("/incidents")
@RequestScoped
public class IncidentResource {

    private static final int MAX_LIMIT = 200;

    @Inject
    MongoDatabase db;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("status") String status,
                         @QueryParam("limit") @DefaultValue("50") int limit) {
        if (status != null && !status.equals(Incident.OPEN) && !status.equals(Incident.CLOSED)) {
            // a plain 400: a wrong parameter is the caller's mistake, not a server error to log
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "status must be open or closed")).build();
        }
        Bson filter = status == null ? Filters.empty() : Filters.eq("status", status);
        List<Incident> result = new ArrayList<>();
        db.getCollection("incidents").find(filter)
                .sort(Sorts.descending("openedAt"))
                .limit(Math.max(1, Math.min(limit, MAX_LIMIT)))
                .forEach(doc -> result.add(Incident.fromDocument(doc)));
        return Response.ok(result).build();
    }
}
