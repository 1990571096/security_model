package com.securitymodel.application.audit;

/**
 * Authenticated identity carried by the JWT.
 *
 * <p>{@code userId} is the stable {@code sys_user.user_id}; it is the only key used for
 * experiment ownership. {@code username} is kept for display and audit snapshots only.
 * Tokens issued before the R1 rollout carry no {@code userId}, in which case it is
 * {@code null} and task writes are rejected until the user signs in again.</p>
 */
public record AuthenticatedUser(Long userId, String username, boolean admin) {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    public static AuthenticatedUser anonymous() {
        return new AuthenticatedUser(null, "anonymousUser", false);
    }

    public static AuthenticatedUser system() {
        return new AuthenticatedUser(null, "system", false);
    }

    public boolean hasStableIdentity() {
        return userId != null;
    }
}
