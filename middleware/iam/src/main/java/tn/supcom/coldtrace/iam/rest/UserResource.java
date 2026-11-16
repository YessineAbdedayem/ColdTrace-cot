package tn.supcom.coldtrace.iam.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.SecurityContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tn.supcom.coldtrace.iam.user.PasswordPolicy;
import tn.supcom.coldtrace.iam.user.Roles;
import tn.supcom.coldtrace.iam.user.User;
import tn.supcom.coldtrace.iam.user.UserStore;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static tn.supcom.coldtrace.iam.rest.AuthResource.error;

/**
 * User management, for administrators only (the back-office will use it).
 *
 * GET    /users              all users
 * GET    /users/{username}
 * POST   /users              {username, name, roles, password}
 * PUT    /users/{username}   {name?, roles?, enabled?, password?}: only the fields given change
 * DELETE /users/{username}
 *
 * There is always at least one enabled administrator left, and nobody can delete their own account.
 */
@Path("/users")
@RequestScoped
@RolesAllowed(Roles.ADMIN)
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    private static final Logger LOG = Logger.getLogger(UserResource.class.getName());

    @Inject
    UserStore users;

    @Inject
    SecurityContext security;

    public record NewUser(String username, String name, Set<String> roles, String password) {
    }

    public record UserChange(String name, Set<String> roles, Boolean enabled, String password) {
    }

    @GET
    public List<User> list() {
        return users.list();
    }

    @GET
    @Path("/{username}")
    public Response get(@PathParam("username") String username) {
        return users.find(username).<Response>map(u -> Response.ok(u).build())
                .orElseGet(() -> error(Response.Status.NOT_FOUND, "no user " + username));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(NewUser req) {
        if (req == null || !PasswordPolicy.validUsername(req.username())) {
            return error(Response.Status.BAD_REQUEST,
                    "username: 3 to 32 characters among lowercase letters, digits, '.', '-', '_'");
        }
        if (!Roles.valid(req.roles())) {
            return error(Response.Status.BAD_REQUEST, "roles: at least one of " + Roles.ALL);
        }
        String problem = PasswordPolicy.problem(req.password(), req.username());
        if (problem != null) {
            return error(Response.Status.BAD_REQUEST, problem);
        }
        if (req.name() != null && req.name().length() > 80) {
            return error(Response.Status.BAD_REQUEST, "name: at most 80 characters");
        }
        if (!users.create(req.username(), req.name(), req.roles(), req.password().toCharArray())) {
            return error(Response.Status.CONFLICT, "the username " + req.username() + " is taken");
        }
        LOG.info("User '" + req.username() + "' " + req.roles() + " created by '" + caller() + "'");
        return Response.created(URI.create("/users/" + req.username()))
                .entity(users.find(req.username()).orElseThrow()).build();
    }

    @PUT
    @Path("/{username}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("username") String username, UserChange req) {
        if (req == null) {
            return error(Response.Status.BAD_REQUEST, "nothing to change");
        }
        User current = users.find(username).orElse(null);
        if (current == null) {
            return error(Response.Status.NOT_FOUND, "no user " + username);
        }
        if (req.roles() != null && !Roles.valid(req.roles())) {
            return error(Response.Status.BAD_REQUEST, "roles: at least one of " + Roles.ALL);
        }
        if (req.password() != null) {
            String problem = PasswordPolicy.problem(req.password(), username);
            if (problem != null) {
                return error(Response.Status.BAD_REQUEST, problem);
            }
        }
        if (req.name() != null && req.name().length() > 80) {
            return error(Response.Status.BAD_REQUEST, "name: at most 80 characters");
        }
        boolean losesAdmin = current.enabled() && current.roles().contains(Roles.ADMIN)
                && ((req.roles() != null && !req.roles().contains(Roles.ADMIN)) || Boolean.FALSE.equals(req.enabled()));
        if (losesAdmin && users.otherEnabledAdmins(username) == 0) {
            return error(Response.Status.CONFLICT, "this is the last enabled administrator: keep at least one");
        }
        users.update(username, req.name(), req.roles(), req.enabled(),
                req.password() == null ? null : req.password().toCharArray());
        LOG.info("User '" + username + "' changed by '" + caller() + "'"
                + (req.roles() != null ? " roles=" + req.roles() : "") + (req.enabled() != null ? " enabled=" + req.enabled() : "")
                + (req.password() != null ? " (new password)" : ""));
        return Response.ok(users.find(username).orElseThrow()).build();
    }

    @DELETE
    @Path("/{username}")
    public Response delete(@PathParam("username") String username) {
        if (username.equals(caller())) {
            return error(Response.Status.CONFLICT, "you cannot delete your own account");
        }
        User current = users.find(username).orElse(null);
        if (current == null) {
            return error(Response.Status.NOT_FOUND, "no user " + username);
        }
        if (current.enabled() && current.roles().contains(Roles.ADMIN) && users.otherEnabledAdmins(username) == 0) {
            return error(Response.Status.CONFLICT, "this is the last enabled administrator: keep at least one");
        }
        users.delete(username);
        LOG.info("User '" + username + "' deleted by '" + caller() + "'");
        return Response.noContent().build();
    }

    /** Only reached with the admin role (@RolesAllowed), so the caller is a real, logged-in user. */
    private String caller() {
        return security.getCallerPrincipal() == null ? "?" : security.getCallerPrincipal().getName();
    }
}
