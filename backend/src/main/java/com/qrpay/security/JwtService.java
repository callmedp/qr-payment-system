package com.qrpay.security;

import com.qrpay.auth.Role;
import com.qrpay.config.QrPayProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Issues and verifies HS256 access tokens. Claims: sub=userId, email, role, mid=merchantId. */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(QrPayProperties props) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.security().jwtSecret()));
        this.ttl = props.security().jwtTtl();
    }

    public IssuedToken issue(AuthUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        var builder = Jwts.builder()
                .subject(user.userId().toString())
                .claim("email", user.email())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt));
        if (user.merchantId() != null) {
            builder.claim("mid", user.merchantId().toString());
        }
        return new IssuedToken(builder.signWith(key).compact(), expiresAt);
    }

    /** @throws io.jsonwebtoken.JwtException if the token is malformed, expired or wrongly signed */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        String merchantId = claims.get("mid", String.class);
        return new AuthUser(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                Role.valueOf(claims.get("role", String.class)),
                merchantId == null ? null : UUID.fromString(merchantId));
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
