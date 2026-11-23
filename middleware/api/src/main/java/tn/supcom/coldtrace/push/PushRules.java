package tn.supcom.coldtrace.push;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Who receives an alert on their phone, and which push services the server may call. Pure rules, unit tested.
 *
 * Recipients (cahier des charges EF-04, "notify the quality manager and the driver concerned"):
 * <ul>
 *   <li>quality managers and administrators: every incident;</li>
 *   <li>operators (drivers): incidents of the sites they are assigned to in the back-office (site.operators).
 *       A site with nobody assigned notifies every operator, so no alert is ever sent to no one.</li>
 * </ul>
 */
public final class PushRules {

    /** Browser push services. The server only POSTs to these, so a subscription cannot point it at another host. */
    public static final List<String> DEFAULT_HOSTS = List.of(
            "fcm.googleapis.com", "updates.push.services.mozilla.com", "push.services.mozilla.com",
            ".push.apple.com", ".notify.windows.com");

    private PushRules() { }

    public static boolean recipient(Collection<String> roles, String username, Collection<String> siteOperators) {
        if (roles == null) return false;
        if (roles.contains("quality") || roles.contains("admin")) return true;
        if (!roles.contains("operator")) return false;
        return siteOperators == null || siteOperators.isEmpty() || siteOperators.contains(username);
    }

    /** An https endpoint on an allowed push service (exact host, or a suffix when the entry starts with "."). */
    public static boolean allowedEndpoint(String endpoint, Collection<String> hosts) {
        if (endpoint == null || endpoint.length() > 2048) return false;
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String allowed : hosts) {
            String a = allowed.trim().toLowerCase(Locale.ROOT);
            if (a.isEmpty()) continue;
            if (a.startsWith(".") ? host.endsWith(a) : host.equals(a)) return true;
        }
        return false;
    }

    public static List<String> hosts(String env) {
        if (env == null || env.isBlank()) return DEFAULT_HOSTS;
        return List.of(env.split(","));
    }

    public static String title(String metric, String sensorId) {
        String what = switch (metric == null ? "" : metric) {
            case "temperature" -> "Température hors plage";
            case "humidity" -> "Humidité hors plage";
            case "door" -> "Porte ouverte";
            case "silence" -> "Capteur muet";
            case "early_warning" -> "Alerte précoce";
            default -> "Incident";
        };
        return "ColdTrace · " + what + " · " + sensorId;
    }
}
