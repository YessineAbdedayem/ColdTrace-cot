package tn.supcom.coldtrace.iam.user;

import java.util.regex.Pattern;

/** What a username and a password must look like. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;
    /** Lowercase letters, digits, dot, dash, underscore: safe in URLs and logs. */
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");

    private PasswordPolicy() {
    }

    /** The name WildFly gives a caller without a token: reserved, never a real user. */
    public static final String ANONYMOUS = "anonymous";

    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches() && !ANONYMOUS.equals(username);
    }

    /** @return null if acceptable, otherwise the reason (shown to the administrator) */
    public static String problem(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "the password must have at least " + MIN_LENGTH + " characters";
        }
        if (password.length() > MAX_LENGTH) {
            return "the password must have at most " + MAX_LENGTH + " characters";
        }
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            return "the password must not contain the username";
        }
        if (password.toLowerCase().startsWith("change-me")) {
            return "the example password from .env.example cannot be used";
        }
        return null;
    }
}
