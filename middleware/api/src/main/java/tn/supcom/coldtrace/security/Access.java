package tn.supcom.coldtrace.security;

/**
 * Which requests need a login, and where their token is. Kept free of servlets, so it can be unit tested.
 *
 * Roles (the IAM's, see middleware/iam/README.md): ADMIN, QUALITY, OPERATOR.
 */
public final class Access {

    public static final String ADMIN = "admin";
    public static final String QUALITY = "quality";
    public static final String OPERATOR = "operator";

    public static final String ALERTS_SOCKET = "/ws/alerts";
    /** The WebSocket's token goes in this query parameter: browsers cannot set headers on a WebSocket. */
    public static final String TOKEN_PARAM = "access_token";

    private Access() {
    }

    /**
     * Everything under /api/ and the alert WebSocket, except the health check (used by Docker and the smoke test).
     * The API's landing page (/) stays public.
     *
     * @param path the request path inside the application, e.g. "/api/sensors"
     */
    public static boolean needsLogin(String path) {
        if (path == null) {
            return false;
        }
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        if (p.equals("/api/health")) {
            return false;
        }
        return p.equals("/api") || p.startsWith("/api/") || p.equals(ALERTS_SOCKET) || p.startsWith(ALERTS_SOCKET + "/");
    }

    /**
     * The token: "Authorization: Bearer ..." anywhere; for the WebSocket also ?access_token=..., since browsers can't
     * send that header there.
     */
    public static String token(String path, String authorizationHeader, String accessTokenParam) {
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = authorizationHeader.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        if (path != null && path.startsWith(ALERTS_SOCKET) && accessTokenParam != null && !accessTokenParam.isBlank()) {
            return accessTokenParam.trim();
        }
        return null;
    }
}
