package tn.supcom.coldtrace.iam;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import tn.supcom.coldtrace.iam.user.PasswordPolicy;
import tn.supcom.coldtrace.iam.user.Roles;
import tn.supcom.coldtrace.iam.user.UserStore;

import java.util.Set;
import java.util.logging.Logger;

/**
 * First users, from the environment (gateway-iot/.env), so nobody has to type a password into the code:
 * <ul>
 *   <li>IAM_ADMIN_USER / IAM_ADMIN_PASSWORD: created only while there is no enabled administrator;</li>
 *   <li>IAM_QUALITY_PASSWORD, IAM_OPERATOR_PASSWORD (optional, dev): users "qualite" and "operateur",
 *       created only if they do not exist.</li>
 * </ul>
 * Existing accounts are never changed: after the first start, passwords are managed through the API.
 */
@ApplicationScoped
public class Bootstrap {

    private static final Logger LOG = Logger.getLogger(Bootstrap.class.getName());

    @Inject
    UserStore users;

    void onStartup(@Observes Startup event) {
        try {
            if (users.enabledAdmins() == 0) {
                String admin = env("IAM_ADMIN_USER", "admin");
                seed(admin, "Administrator", Roles.ADMIN, "IAM_ADMIN_PASSWORD", true);
            }
            seed("qualite", "Responsable qualité", Roles.QUALITY, "IAM_QUALITY_PASSWORD", false);
            seed("operateur", "Opérateur", Roles.OPERATOR, "IAM_OPERATOR_PASSWORD", false);
            LOG.info("IAM ready: " + users.list().size() + " user(s), " + users.enabledAdmins() + " enabled administrator(s)");
        } catch (RuntimeException e) {
            LOG.warning("Could not prepare the users (is MongoDB reachable?): " + e.getMessage());
        }
    }

    private void seed(String username, String name, String role, String passwordVar, boolean required) {
        String password = System.getenv(passwordVar);
        if (password == null || password.isBlank()) {
            if (required) {
                LOG.warning("No administrator, and " + passwordVar + " is not set: nobody can log in. "
                        + "Set it in gateway-iot/.env, then restart the iam service.");
            }
            return;
        }
        if (users.find(username).isPresent()) {
            return;
        }
        String problem = PasswordPolicy.problem(password, username);
        if (problem != null) {
            LOG.warning(passwordVar + " refused (" + problem + "): user '" + username + "' not created");
            return;
        }
        users.create(username, name, Set.of(role), password.toCharArray());
        LOG.info("Created user '" + username + "' (" + role + ") from " + passwordVar);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim().toLowerCase();
    }
}
