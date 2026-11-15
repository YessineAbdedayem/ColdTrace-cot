package tn.supcom.coldtrace.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * DEVELOPMENT ONLY: forwards fault commands to the sensor simulator, so the monitor page (/monitor/) can trigger incidents.
 * The browser cannot call the simulator itself (another origin, no CORS).
 *
 * Disabled (404) unless SIM_CONTROL_URL is set, e.g. http://simulator:4000 in the Compose stack.
 * Only the simulator's known commands are forwarded, after validation.
 */
@Path("/dev/faults")
@ApplicationScoped
public class DevFaultResource {

    private static final Logger LOG = Logger.getLogger(DevFaultResource.class.getName());
    private static final Set<String> FAULTS = Set.of("door_open", "temp_rise", "silent", "none");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final String baseUrl = System.getenv("SIM_CONTROL_URL");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Jsonb jsonb = JsonbBuilder.create();

    public record FaultRequest(String sensorId, String siteId, String fault) {
    }

    /** The simulator's view of each sensor, including its active fault. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response sensors() {
        return forward("GET", "/sensors", null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response set(FaultRequest req) {
        if (req == null || req.fault() == null || !FAULTS.contains(req.fault())) {
            return badRequest("fault must be one of door_open, temp_rise, silent, none");
        }
        boolean bySensor = req.sensorId() != null && ID.matcher(req.sensorId()).matches();
        boolean bySite = req.siteId() != null && ID.matcher(req.siteId()).matches();
        if (bySensor == bySite) {
            return badRequest("give either a valid sensorId or a valid siteId");
        }
        String body = jsonb.toJson(bySensor
                ? Map.of("sensorId", req.sensorId(), "fault", req.fault())
                : Map.of("siteId", req.siteId(), "fault", req.fault()));
        return forward("POST", "/faults", body);
    }

    @POST
    @Path("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clear() {
        return forward("POST", "/faults/clear", "{}");
    }

    private Response forward(String method, String path, String body) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "fault control disabled: set SIM_CONTROL_URL to enable it")).build();
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");
            b = "POST".equals(method) ? b.POST(HttpRequest.BodyPublishers.ofString(body)) : b.GET();
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return Response.status(r.statusCode()).type(MediaType.APPLICATION_JSON).entity(r.body()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable(e);
        } catch (Exception e) {
            return unreachable(e);
        }
    }

    /** A plain 400 with a reason: invalid input is the caller's mistake, not a server error to log. */
    private static Response badRequest(String reason) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", reason)).build();
    }

    private Response unreachable(Exception e) {
        LOG.log(Level.FINE, "Simulator unreachable at " + baseUrl, e);
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "simulator unreachable at " + baseUrl)).build();
    }
}
