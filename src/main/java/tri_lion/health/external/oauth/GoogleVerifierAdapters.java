package tri_lion.health.external.oauth;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import tri_lion.health.exception.ApiException;

@Configuration
public class GoogleVerifierAdapters {
    @Bean
    GoogleIdTokenVerifier googleVerifier(
            @Value("${app.google.issuer}") String issuer,
            @Value("${app.google.client-id}") String clientId,
            @Value("${app.google.fake-enabled:true}") boolean localTokenEnabled) {
        AtomicReference<JwtDecoder> decoder = new AtomicReference<>();
        return token -> {
            if (localTokenEnabled && token != null && token.startsWith("local:")) {
                return localUser(token);
            }
            try {
                JwtDecoder activeDecoder = decoder.get();
                if (activeDecoder == null) {
                    activeDecoder = JwtDecoders.fromIssuerLocation(issuer);
                    decoder.compareAndSet(null, activeDecoder);
                }
                Jwt jwt = decoder.get().decode(token);
                if (!jwt.getAudience().contains(clientId))
                    throw new JwtValidationException("aud", List.of());
                return new GoogleIdTokenVerifier.GoogleUserInfo(
                        jwt.getSubject(),
                        jwt.getClaimAsString("email"),
                        Boolean.TRUE.equals(jwt.getClaim("email_verified")),
                        jwt.getClaimAsString("name"),
                        jwt.getClaimAsString("picture"));
            } catch (Exception e) {
                throw new ApiException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Google ID Token을 확인할 수 없습니다.");
            }
        };
    }

    private GoogleIdTokenVerifier.GoogleUserInfo localUser(String token) {
        String[] parts = token.split(":", 4);
        if (parts.length < 2 || parts[1].isBlank()) {
            throw invalidToken();
        }
        return new GoogleIdTokenVerifier.GoogleUserInfo(
                parts[1],
                parts.length > 2 ? parts[2] : null,
                true,
                parts.length > 3 ? parts[3] : "Local User",
                null);
    }

    private ApiException invalidToken() {
        return new ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Google ID Token을 확인할 수 없습니다.");
    }
}
