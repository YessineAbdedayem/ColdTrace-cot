package tn.supcom.coldtrace.iam.rest;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tn.supcom.coldtrace.iam.auth.LoginGuard;
import tn.supcom.coldtrace.iam.token.TokenService;
import tn.supcom.coldtrace.iam.user.PasswordPolicy;
import tn.supcom.coldtrace.iam.user.Roles;
import tn.supcom.coldtrace.iam.user.User;
import tn.supcom.coldtrace.iam.user.UserStore;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Login and the caller's own account.
 *
 * POST /auth/login           {username, password} -> {accessToken, tokenType, expiresIn, expiresAt, user}
 * GET  /auth/me              the logged-in user (Authorization: Bearer ...)
 * POST /auth/password        {currentPassword, newPassword}: change one's own password
 * GET  /auth/public-key      the public key that checks the tokens (PEM), for the other services
 */
@Path("/auth")
@RequestScoped
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class.getName());

    @Inject
    IdentityStoreHandler identityStores;

    @Inject
    SecurityContext security;

    @Inject
    LoginGuard guard;

    @Inject
    TokenService tokens;

    @Inject
    UserStore users;

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, String expiresAt, User user) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null
                || req.username().length() > 64 || req.password().length() > PasswordPolicy.MAX_LENGTH) {
            return error(Response.Status.BAD_REQUEST, "username and password are required");
        }
        String username = req.username().strip().toLowerCase();
        Instant now = Instant.now();
        Instant locked = guard.lockedUntil(username, now);
        if (locked != null) {
            long wait = Math.max(1, Duration.between(now, locked).toSeconds());
            return Response.status(429).header("Retry-After", wait)
                    .entity(Map.of("error", "too many failed attempts: try again in " + wait + " s")).build();
        }

        CredentialValidationResult result = identityStores.validate(
                new UsernamePasswordCredential(username, req.password()));
        if (result.getStatus() != CredentialValidationResult.Status.VALID) {
            guard.failed(username, now);
            LOG.info("Login refused for '" + username + "'");
            // the same answer whether the username exists or not
            return error(Response.Status.UNAUTHORIZED, "invalid username or password");
        }
        guard.succeeded(username);
        User user = users.find(username).orElseThrow();
        TokenService.Issued issued = tokens.issue(user.username(), user.name(), user.roles());
        LOG.info("Login: '" + username + "' " + user.roles());
        return Response.ok(new LoginResponse(issued.token(), "Bearer", issued.expiresIn(),
                issued.expiresAt().toString(), user)).build();
    }

    /**
     * The logged-in caller, or null. WildFly gives requests without a token a principal named "anonymous" instead of
     * null; that name is reserved (PasswordPolicy), so it can never be a real user.
     */
    private Principal caller() {
        Principal p = security.getCallerPrincipal();
        return p == null || PasswordPolicy.ANONYMOUS.equals(p.getName()) ? null : p;
    }

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response me() {
        Principal caller = caller();
        if (caller == null) {
            return error(Response.Status.UNAUTHORIZED, "log in first: Authorization: Bearer <token>");
        }
        return users.find(caller.getName())
                .<Response>map(u -> Response.ok(u).build())
                // a valid token for a user deleted since: show what the token says
                .orElseGet(() -> Response.ok(Map.of("username", caller.getName(), "roles", rolesOfCaller())).build());
    }

    @POST
    @Path("/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response changePassword(PasswordChange req) {
        Principal caller = caller();
        if (caller == null) {
            return error(Response.Status.UNAUTHORIZED, "log in first: Authorization: Bearer <token>");
        }
        if (req == null || req.currentPassword() == null || req.newPassword() == null) {
            return error(Response.Status.BAD_REQUEST, "currentPassword and newPassword are required");
        }
        String username = caller.getName();
        if (users.check(username, req.currentPassword().toCharArray()).isEmpty()) {
            guard.failed(username, Instant.now());
            return error(Response.Status.FORBIDDEN, "the current password is wrong");
        }
        String problem = PasswordPolicy.problem(req.newPassword(), username);
        if (problem != null) {
            return error(Response.Status.BAD_REQUEST, problem);
        }
        users.update(username, null, null, null, req.newPassword().toCharArray());
        LOG.info("Password changed by '" + username + "'");
        return Response.noContent().build();
    }

    @GET
    @Path("/public-key")
    @Produces(MediaType.TEXT_PLAIN)
    public Response publicKey() {
        return Response.ok(tokens.publicKeyPem()).header("X-Key-Id", tokens.keyId()).build();
    }

    private Set<String> rolesOfCaller() {
        Set<String> roles = new TreeSet<>();
        for (String r : Roles.ALL) {
            if (security.isCallerInRole(r)) {
                roles.add(r);
            }
        }
        return roles;
    }

    static Response error(Response.Status status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build();
    }
}
