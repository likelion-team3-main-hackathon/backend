package tri_lion.health.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer "))
            try {
                var c = jwt.parse(h.substring(7));
                if (!"refresh".equals(c.get("type")))
                    SecurityContextHolder.getContext()
                            .setAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                            c.getSubject(), null, List.of()));
            } catch (Exception ignored) {
            }
        chain.doFilter(req, res);
    }
}
