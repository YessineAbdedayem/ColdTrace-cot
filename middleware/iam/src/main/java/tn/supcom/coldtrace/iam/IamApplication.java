package tn.supcom.coldtrace.iam;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/** The IAM's REST API at the root: /auth/..., /users/..., /health. */
@ApplicationPath("/")
public class IamApplication extends Application {

    @Path("/health")
    public static class Health {
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Map<String, String> health() {
            return Map.of("status", "ok");
        }
    }
}
