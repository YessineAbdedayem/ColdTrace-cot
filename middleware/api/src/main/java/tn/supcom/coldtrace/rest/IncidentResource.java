package tn.supcom.coldtrace.rest;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import tn.supcom.coldtrace.alert.AlertBroadcaster;
import tn.supcom.coldtrace.alert.AlertEvent;
import tn.supcom.coldtrace.model.Incident;
import tn.supcom.coldtrace.model.IncidentAcknowledgement;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GET /api/incidents?status=open|closed&limit=50 : newest first. Without status, both. */
@Path("/incidents")
@RequestScoped
public class IncidentResource {

    private static final int MAX_LIMIT = 200;
    private static final Set<String> ACK_FIELDS = Set.of("operator", "comment");

    @Inject
    MongoDatabase db;

    @Inject
    AlertBroadcaster broadcaster;

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

    @POST
    @Path("{id}/ack")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response acknowledge(@PathParam("id") String id, String body) {
        if (id == null || !id.matches("[0-9a-fA-F]{24}")) {
            return error(Response.Status.BAD_REQUEST, "id must be a 24-character hexadecimal ObjectId");
        }

        JsonObject request;
        try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
            request = reader.readObject();
        } catch (JsonException e) {
            return error(Response.Status.BAD_REQUEST, "request body must be a JSON object");
        }
        if (!ACK_FIELDS.containsAll(request.keySet())) {
            return error(Response.Status.BAD_REQUEST, "only operator and comment are allowed");
        }

        Object operator = requestValue(request, "operator");
        Object comment = requestValue(request, "comment");

        IncidentAcknowledgement input;
        try {
            input = IncidentAcknowledgement.validate(operator, comment);
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }

        ObjectId objectId = new ObjectId(id);
        Document updated = db.getCollection("incidents").findOneAndUpdate(
                Filters.and(
                        Filters.eq("_id", objectId),
                        Filters.eq("status", Incident.OPEN),
                        Filters.exists("acknowledgedAt", false)),
                Updates.combine(
                        Updates.set("acknowledgedAt", new Date()),
                        Updates.set("acknowledgedBy", input.operator()),
                        Updates.set("comment", input.comment())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        if (updated == null) {
            Document current = db.getCollection("incidents").find(Filters.eq("_id", objectId)).first();
            if (current == null) {
                return error(Response.Status.NOT_FOUND, "incident not found");
            }
            if (!Incident.OPEN.equals(current.getString("status"))
                    || current.containsKey("acknowledgedAt")) {
                return error(Response.Status.CONFLICT, "incident is already closed or acknowledged");
            }
            return error(Response.Status.CONFLICT, "incident could not be acknowledged");
        }

        Incident incident = Incident.fromDocument(updated);
        broadcaster.broadcast(new AlertEvent(AlertEvent.ACKNOWLEDGED, incident));
        return Response.ok(incident).build();
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    private static Object requestValue(JsonObject request, String key) {
        if (!request.containsKey(key) || request.isNull(key)) {
            return null;
        }
        JsonValue value = request.get(key);
        return value.getValueType() == JsonValue.ValueType.STRING ? request.getString(key) : value;
    }
}
