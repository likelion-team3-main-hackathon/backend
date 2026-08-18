package tri_lion.health.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import tri_lion.health.security.JwtAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationFilter jwt) throws Exception {
        return http.csrf(x -> x.disable())
                .cors(c -> {})
                .sessionManagement(x -> x.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        x ->
                                x.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(
                                                                HttpStatus.UNAUTHORIZED.value()))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(
                                                                HttpStatus.FORBIDDEN.value())))
                .authorizeHttpRequests(
                        x ->
                                x.requestMatchers(
                                                "/actuator/health/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**",
                                                "/api/v1/auth/oauth/google",
                                                "/api/v1/auth/token/refresh")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.frontend-origin}") String origin) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }
}
