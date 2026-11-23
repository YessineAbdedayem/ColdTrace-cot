package tn.supcom.coldtrace.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.bson.Document;
import tn.supcom.coldtrace.business.ApiProblem;
import tn.supcom.coldtrace.business.Catalog;
import tn.supcom.coldtrace.push.PushService;
import tn.supcom.coldtrace.security.Access;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web Push subscriptions of the logged-in user's devices (alerts on the phone, cahier des charges EF-04).
 *
 * GET    /api/push              {enabled, publicKey, devices}: the key the browser subscribes with
 * POST   /api/push/subscriptions  the browser's PushSubscription JSON: {endpoint, keys: {p256dh, auth}}
 * DELETE /api/push/subscriptions  {endpoint}: this device stops receiving alerts
 * POST   /api/push/test           a test notification to the caller's devices
 *
 * The roles saved with a subscription come from the token, not from the body.
 */
@Path("/push")
@RequestScoped
@RolesAllowed({Access.ADMIN, Access.QUALITY, Access.OPERATOR})
@Produces(MediaType.APPLICATION_JSON)
public class PushResource {

    @Inject PushService push;
    @Context SecurityContext security;

    @GET
    public Map<String, Object> status() {
        return push.enabled()
                ? Map.of("enabled", true, "publicKey", push.publicKey(), "devices", push.devices(user()))
                : Map.of("enabled", false, "devices", 0);
    }

    @POST
    @Path("subscriptions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> subscribe(String body, @HeaderParam("User-Agent") String userAgent) {
        Document d = Catalog.body(body, "endpoint", "keys", "expirationTime");
        if (!(d.get("endpoint") instanceof String endpoint)) throw ApiProblem.invalid("endpoint is required");
        if (!(d.get("keys") instanceof Document keys) || !(keys.get("p256dh") instanceof String p256dh)
                || !(keys.get("auth") instanceof String auth)) {
            throw ApiProblem.invalid("keys.p256dh and keys.auth are required");
        }
        push.subscribe(user(), roles(), endpoint, p256dh, auth, userAgent);
        return Map.of("subscribed", true, "devices", push.devices(user()));
    }

    @DELETE
    @Path("subscriptions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> unsubscribe(String body) {
        Document d = Catalog.body(body, "endpoint");
        if (!(d.get("endpoint") instanceof String endpoint)) throw ApiProblem.invalid("endpoint is required");
        return Map.of("removed", push.unsubscribe(user(), endpoint), "devices", push.devices(user()));
    }

    @POST
    @Path("test")
    public Map<String, Object> test() {
        return Map.of("sent", push.test(user()));
    }

    private String user() {
        return security.getUserPrincipal().getName();
    }

    private List<String> roles() {
        List<String> roles = new ArrayList<>();
        for (String r : List.of(Access.ADMIN, Access.QUALITY, Access.OPERATOR)) {
            if (security.isUserInRole(r)) roles.add(r);
        }
        return roles;
    }
}
