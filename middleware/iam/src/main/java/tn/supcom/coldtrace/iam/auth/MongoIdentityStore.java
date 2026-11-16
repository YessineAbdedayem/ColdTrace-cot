package tn.supcom.coldtrace.iam.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;
import tn.supcom.coldtrace.iam.user.UserStore;

import java.util.HashSet;

/**
 * Jakarta Security identity store: checks a username and password against coldtrace.users, and gives the user's
 * roles as groups. Used by POST /auth/login through the container's IdentityStoreHandler.
 */
@ApplicationScoped
public class MongoIdentityStore implements IdentityStore {

    @Inject
    UserStore users;

    public CredentialValidationResult validate(UsernamePasswordCredential credential) {
        return users.check(credential.getCaller(), credential.getPassword().getValue())
                .map(u -> new CredentialValidationResult(u.username(), new HashSet<>(u.roles())))
                .orElse(CredentialValidationResult.INVALID_RESULT);
    }
}
