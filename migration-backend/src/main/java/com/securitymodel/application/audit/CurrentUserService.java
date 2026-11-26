package com.securitymodel.application.audit;

import com.securitymodel.application.audit.AdminRequiredException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

    /**
     * Current identity. Falls back to {@code system} outside an authenticated request
     * (startup recovery, async executor) exactly like before R1.
     */
    public AuthenticatedUser identity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return AuthenticatedUser.system();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            return AuthenticatedUser.system();
        }
        if ("anonymousUser".equals(name)) {
            return AuthenticatedUser.anonymous();
        }
        return new AuthenticatedUser(null, name, hasAuthority(authentication, AuthenticatedUser.ROLE_ADMIN));
    }

    public String username() {
        return identity().username();
    }

    /** Stable {@code sys_user.user_id}, or {@code null} for legacy tokens and background threads. */
    public Long userId() {
        return identity().userId();
    }

    public boolean isAdmin() {
        return identity().admin();
    }

    public AuthenticatedUser requireStableIdentity() {
        AuthenticatedUser identity = identity();
        if (!identity.hasStableIdentity()) {
            throw new AccessDeniedException("登录状态无效，请重新登录");
        }
        return identity;
    }

    public AuthenticatedUser requireAdmin() {
        AuthenticatedUser identity = requireStableIdentity();
        if (!identity.admin()) {
            throw new AdminRequiredException("此操作仅限管理员");
        }
        return identity;
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
