package io.auralis.bootstrap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Transport boundary only. Subject authorization and CSRF ownership are implemented by BE-006. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpBoundary extends OncePerRequestFilter {
    private final OnlineProperties properties;
    private final HttpErrors errors;
    private final io.auralis.session.api.SubjectSession subjects;

    public HttpBoundary(
            OnlineProperties properties,
            HttpErrors errors,
            io.auralis.session.api.SubjectSession subjects) {
        this.properties = properties;
        this.errors = errors;
        this.subjects = subjects;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        String path = request.getRequestURI();
        if (path.contains("%")
                || path.contains(";")
                || path.contains("//")
                || path.contains("/../")) {
            errors.write(request, response, 400, "INVALID_PAYLOAD");
            return;
        }
        if (path.startsWith("/internal/")) {
            String supplied = request.getHeader("Authorization");
            String expected = "Bearer " + properties.getServiceToken();
            if (supplied == null
                    || !MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            supplied.getBytes(StandardCharsets.UTF_8))) {
                errors.write(request, response, 401, "UNAUTHENTICATED");
                return;
            }
            if (!"coordinator".equals(request.getHeader("X-Auralis-Audience"))
                    || request.getHeader("Origin") != null) {
                errors.write(request, response, 403, "FORBIDDEN");
                return;
            }
        } else if (path.startsWith("/v1/")) {
            String origin = request.getHeader("Origin");
            boolean write = !Set.of("GET", "HEAD").contains(request.getMethod());
            if ((write || origin != null) && !properties.getProductOrigin().equals(origin)) {
                errors.write(
                        request, response, path.equals("/v1/bootstrap") ? 403 : 400, "FORBIDDEN");
                return;
            }
            if (!path.equals("/v1/bootstrap")) {
                String token;
                try {
                    token = SubjectCookies.credential(request);
                } catch (IllegalArgumentException invalid) {
                    errors.write(request, response, 400, "UNAUTHENTICATED");
                    return;
                }
                var identity = subjects.authenticate(token);
                if (identity.isEmpty()) {
                    errors.write(request, response, 400, "UNAUTHENTICATED");
                    return;
                }
                if (write && !subjects.validCsrf(token, request.getHeader("X-CSRF-Token"))) {
                    errors.write(request, response, 400, "FORBIDDEN");
                    return;
                }
                if (write
                        && (request.getHeader("Idempotency-Key") == null
                                || request.getHeader("Idempotency-Key").isBlank()
                                || request.getHeader("Idempotency-Key").length() > 160)) {
                    errors.write(request, response, 400, "INVALID_PAYLOAD");
                    return;
                }
                request.setAttribute(SubjectCookies.IDENTITY_ATTRIBUTE, identity.get());
            }
        } else if (!Set.of(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")
                .contains(path)) {
            errors.write(request, response, 404, "INVALID_PAYLOAD");
            return;
        }
        chain.doFilter(request, response);
    }
}
