package tn.supcom.coldtrace.iam.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tn.supcom.coldtrace.iam.token.Jwt;
import tn.supcom.coldtrace.iam.token.TokenService;

import java.util.logging.Logger;

/**
 * Jakarta Security authentication mechanism: "Authorization: Bearer &lt;token&gt;" logs the caller in with the token's
 * subject and roles, so {@code @RolesAllowed} works. A request without a token stays anonymous (login, public key,
 * health); a request with an invalid token is refused with 401.
 */
@ApplicationScoped
public class BearerTokenMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(BearerTokenMechanism.class.getName());

    @Inject
    TokenService tokens;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return context.doNothing();
        }
        try {
            Jwt.Claims claims = tokens.verify(header.substring(7).trim());
            return context.notifyContainerAboutLogin(claims.subject(), claims.groups());
        } catch (Jwt.InvalidTokenException e) {
            LOG.fine("Token refused: " + e.getMessage());
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            return context.responseUnauthorized();
        }
    }
}
