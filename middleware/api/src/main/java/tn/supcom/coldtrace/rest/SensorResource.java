package tn.supcom.coldtrace.rest;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.ingestion.SensorState;
import java.time.Instant;
import java.util.Date;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import tn.supcom.coldtrace.model.Measurement;
import tn.supcom.coldtrace.model.Threshold;
import tn.supcom.coldtrace.security.Access;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GET /api/sensors: every known sensor with its profile limits, latest reading and recent temperatures.
 * One call gives a dashboard everything it needs to draw the sensors.
 */
@Path("/sensors")
@RequestScoped
@RolesAllowed({Access.ADMIN, Access.QUALITY, Access.OPERATOR})
public class SensorResource {

    /** Number of recent readings returned per sensor (2 minutes at one reading every 5 s). */
    private static final int RECENT = 24;

    @Inject
    MongoDatabase db;
    @Inject Catalog catalog;
    @Inject SensorState states;

    public record Limits(Double temperatureMin, Double temperatureMax, Double humidityMin, Double humidityMax) {
    }

    public record Reading(String ts, Double temperature, Double humidity, Boolean doorOpen, Boolean replayed) {
    }

    public record SensorView(String id, String siteId, String name, String profile, Limits limits,
                             Reading latest, List<Double> recentTemperatures, String type, boolean active,
                             String status, String lastEventAt, String lastIngestedAt, Long ageSeconds,
                             Reading lastMeasurement) { }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<SensorView> list(@QueryParam("siteId") String siteId,
                                @QueryParam("offset") @DefaultValue("0") int offset,
                                @QueryParam("limit") @DefaultValue("200") int limit) {
        if (siteId != null) Catalog.id(siteId);
        List<SensorView> result = new ArrayList<>();
        db.getCollection("sensors").find(siteId == null ? Filters.empty() : Filters.eq("siteId", siteId))
                .sort(Sorts.ascending("_id")).skip(Catalog.offset(offset)).limit(Catalog.limit(limit))
                .forEach(doc -> result.add(view(doc)));
        return result;
    }

    @GET @Path("{id}") @Produces(MediaType.APPLICATION_JSON)
    public SensorView get(@PathParam("id") String id) { return view(catalog.require("sensors", id)); }

    @POST @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RolesAllowed(Access.ADMIN)
    public Response create(String body) {
        Document d = catalog.sensor(null, body, true);
        return Response.created(URI.create("/api/sensors/" + d.getString("_id"))).entity(view(d)).build();
    }

    @PUT @Path("{id}") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @RolesAllowed(Access.ADMIN)
    public SensorView update(@PathParam("id") String id, String body) { return view(catalog.sensor(id, body, false)); }

    @DELETE @Path("{id}") @Produces(MediaType.APPLICATION_JSON) @RolesAllowed(Access.ADMIN)
    public SensorView deactivate(@PathParam("id") String id) { return view(catalog.deactivate("sensors", id)); }

    private SensorView view(Document sensor) {
        Document last = states.latest(sensor);
        String status = states.status(sensor, last);
        boolean online = "online".equals(status);
        Document threshold = catalog.threshold(sensor);
        Threshold t = threshold == null ? null : Threshold.fromDocument(threshold);
        Limits limits = t == null ? null : new Limits(t.getTemperatureMin(), t.getTemperatureMax(), t.getHumidityMin(), t.getHumidityMax());
        List<Double> temperatures = new ArrayList<>();
        if (online) {
            db.getCollection("measurements").find(Filters.and(SensorState.eligible(),
                    Filters.eq("siteId", sensor.getString("siteId")), Filters.eq("sensorId", sensor.getString("_id")),
                    Filters.lte("ts", last.getDate("ts"))))
                    .sort(Sorts.descending("ts")).limit(RECENT).forEach(m -> {
                        if (m.get("temperature") instanceof Number n) temperatures.add(n.doubleValue());
                    });
            Collections.reverse(temperatures);
        }
        Date eventTime = last == null ? null : last.getDate("ts");
        Date ingested = last == null ? null : last.getDate("ingestedAt");
        Reading previous = last == null ? null : toReading(Measurement.fromDocument(last));
        return new SensorView(sensor.getString("_id"), sensor.getString("siteId"), sensor.getString("name"),
                catalog.profile(sensor), limits, online ? previous : null, temperatures,
                sensor.getString("type"), catalog.enabled(sensor), status,
                eventTime == null ? null : eventTime.toInstant().toString(),
                ingested == null ? null : ingested.toInstant().toString(),
                eventTime == null ? null : Math.max(0, Instant.now().getEpochSecond() - eventTime.toInstant().getEpochSecond()), previous);
    }

    private static Reading toReading(Measurement m) {
        return new Reading(m.getTs(), m.getTemperature(), m.getHumidity(), m.getDoorOpen(), m.getReplayed());
    }
}
