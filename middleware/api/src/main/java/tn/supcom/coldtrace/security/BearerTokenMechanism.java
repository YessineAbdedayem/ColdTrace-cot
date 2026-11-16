package tn.supcom.coldtrace.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Jakarta Security authentication mechanism of the API: checks the IAM's token, then logs the caller in with its
 * roles, so {@code @RolesAllowed} works on the REST resources.
 *
 * <ul>
 *   <li>/api/health and the landing page: no login;</li>
 *   <li>everything else under /api/, and /ws/alerts: a valid token, else 401;</li>
 *   <li>the IAM's key not available yet (IAM never reached): 503.</li>
 * </ul>
 */
@ApplicationScoped
public class BearerTokenMechanism implements HttpAuthenticationMechanism {

    public static final String ISSUER = "coldtrace-iam";
    public static final String AUDIENCE = "coldtrace";
    private static final Logger LOG = Logger.getLogger(BearerTokenMechanism.class.getName());

    @Inject
    IamPublicKey iamKey;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!Access.needsLogin(path)) {
            return context.doNothing();
        }
        // the query parameter only for the WebSocket: reading parameters elsewhere could consume a request body
        String param = path.startsWith(Access.ALERTS_SOCKET) ? request.getParameter(Access.TOKEN_PARAM) : null;
        String token = Access.token(path, request.getHeader("Authorization"), param);
        if (token == null) {
            return unauthorized(response, context, "log in first: Authorization: Bearer <token>");
        }
        PublicKey key = iamKey.get();
        if (key == null) {
            try {
                response.setContentType("application/json");
                response.setStatus(503);
                response.getWriter().write(iamKey.configured()
                        ? "{\"error\":\"the login server (IAM) cannot be reached yet: try again in a moment\"}"
                        : "{\"error\":\"authentication is not configured: set IAM_URL on the API\"}");
            } catch (IOException e) {
                LOG.fine("Could not write the 503 answer: " + e.getMessage());
            }
            return AuthenticationStatus.SEND_FAILURE;
        }
        try {
            Jwt.Claims claims = Jwt.verify(token, key, ISSUER, AUDIENCE, Instant.now());
            return context.notifyContainerAboutLogin(claims.subject(), claims.groups());
        } catch (Jwt.InvalidTokenException e) {
            LOG.fine("Token refused on " + path + ": " + e.getMessage());
            return unauthorized(response, context, "invalid or expired token: log in again");
        }
    }

    private static AuthenticationStatus unauthorized(HttpServletResponse response, HttpMessageContext context,
                                                     String message) {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"coldtrace\"");
        try {
            response.setContentType("application/json");
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"" + message + "\"}");
        } catch (IOException e) {
            LOG.fine("Could not write the 401 answer: " + e.getMessage());
        }
        return AuthenticationStatus.SEND_FAILURE;
    }
}
