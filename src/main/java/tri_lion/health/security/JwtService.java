package tri_lion.health.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final javax.crypto.SecretKey key;
    private final String issuer;
    private final long accessSeconds, refreshSeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.access-seconds}") long a,
            @Value("${app.jwt.refresh-seconds}") long r) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessSeconds = a;
        this.refreshSeconds = r;
    }

    public String access(Long id, String role, String status) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(id.toString())
                .claim("role", role)
                .claim("status", status)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessSeconds)))
                .signWith(key)
                .compact();
    }

    public String refresh(String sessionId, Long id) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(id.toString())
                .id(sessionId)
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(refreshSeconds)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public long accessSeconds() {
        return accessSeconds;
    }

    public long refreshSeconds() {
        return refreshSeconds;
    }
}
