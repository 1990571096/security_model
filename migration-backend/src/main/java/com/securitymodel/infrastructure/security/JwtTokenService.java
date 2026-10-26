package com.securitymodel.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {
    private final SecretKey signingKey;
    private final Duration validity;

    public JwtTokenService(
            @Value("${security-model.auth.jwt-secret}") String secret,
            @Value("${security-model.auth.token-validity:PT8H}") Duration validity) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.validity = validity;
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder().subject(username).claim("userId", userId)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(validity)))
                .signWith(signingKey).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
    }
}
