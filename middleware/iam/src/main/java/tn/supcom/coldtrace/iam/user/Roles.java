package tn.supcom.coldtrace.iam.user;

import java.util.Set;

/**
 * The three profiles of the specification (cahier des charges, "Utilisateurs"):
 * <ul>
 *   <li>{@link #ADMIN} administrator: configuration in the back-office (sensors, sites, thresholds, users);</li>
 *   <li>{@link #QUALITY} quality manager: alerts, analysis, reports;</li>
 *   <li>{@link #OPERATOR} driver or operator: the alerts of his vehicle or zone.</li>
 * </ul>
 * They travel in the token's "groups" claim, and the services check them with {@code @RolesAllowed}.
 */
public final class Roles {

    public static final String ADMIN = "admin";
    public static final String QUALITY = "quality";
    public static final String OPERATOR = "operator";

    public static final Set<String> ALL = Set.of(ADMIN, QUALITY, OPERATOR);

    private Roles() {
    }

    /** At least one role, and only known ones. */
    public static boolean valid(Set<String> roles) {
        return roles != null && !roles.isEmpty() && ALL.containsAll(roles);
    }
}
