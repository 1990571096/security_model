package com.securitymodel.infrastructure.security;

import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the caller from the JWT and, for tokens that carry a stable {@code userId},
 * re-reads the account state from {@code sys_user} on every request.
 *
 * <p>Why read the database instead of trusting the token: a signed token stays valid until
 * it expires, so a disabled account or a revoked administrator would otherwise keep full
 * access for up to {@code token-validity} (8h by default). The row is the authority for
 * {@code enabled} and {@code is_admin}; the token only proves who signed in.
 * Trade-off accepted for a single-machine deployment: one indexed primary-key read per
 * authenticated request, and no revocation list to maintain.</p>
 *
 * <p>Tokens issued before R1 carry no {@code userId}: they still authenticate for shared
 * read-only resources but cannot own or write tasks, and never carry {@code ROLE_ADMIN}.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokenService;
    private final SysUserMapper userMapper;

    public JwtAuthenticationFilter(JwtTokenService tokenService, SysUserMapper userMapper) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        // Never inherit another request's identity: a rejected or missing token must leave the
        // request unauthenticated instead of reusing a stale SecurityContext.
        SecurityContextHolder.clearContext();
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = tokenService.parse(authorization.substring(7));
                AuthenticatedUser identity = resolve(claims);
                if (identity != null) {
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority(AuthenticatedUser.ROLE_USER));
                    if (identity.admin()) {
                        authorities.add(new SimpleGrantedAuthority(AuthenticatedUser.ROLE_ADMIN));
                    }
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(identity, null, authorities));
                }
            } catch (RuntimeException ignored) {
                // Authorization is rejected by Spring Security when the token is invalid.
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * @return the identity to authenticate, or {@code null} when the account no longer exists
     *         or is disabled. A null return leaves the request unauthenticated on purpose.
     */
    private AuthenticatedUser resolve(Claims claims) {
        Long userId = userIdClaim(claims);
        String subject = claims.getSubject();
        if (userId == null) {
            return new AuthenticatedUser(null, subject, false);
        }
        SysUser user = userMapper.findStateById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return null;
        }
        return new AuthenticatedUser(user.getUserId(), user.getUsername(),
                user.getAdmin() != null && user.getAdmin() == 1);
    }

    private Long userIdClaim(Claims claims) {
        Object raw = claims.get("userId");
        return raw instanceof Number number ? number.longValue() : null;
    }
}
