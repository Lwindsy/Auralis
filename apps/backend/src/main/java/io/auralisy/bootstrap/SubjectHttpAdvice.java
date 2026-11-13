package io.auralisy.bootstrap;

import io.auralisy.persistence.PersistenceRejected;
import io.auralisy.session.api.SubjectSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class SubjectHttpAdvice implements ResponseBodyAdvice<Object> {
    private final HttpErrors errors;
    private final SubjectSession subjects;
    private final Clock clock;
    private final OnlineProperties properties;

    public SubjectHttpAdvice(
            HttpErrors errors, SubjectSession subjects, Clock clock, OnlineProperties properties) {
        this.errors = errors;
        this.subjects = subjects;
        this.clock = clock;
        this.properties = properties;
    }

    @ExceptionHandler(PersistenceRejected.class)
    public void rejected(
            PersistenceRejected rejected, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        String code = rejected.getMessage();
        int status =
                request.getRequestURI().equals("/v1/bootstrap")
                        ? 403
                        : code.equals("DEPENDENCY_UNAVAILABLE")
                                ? 503
                                : code.contains("CONFLICT") ? 409 : 400;
        errors.write(request, response, status, code);
    }

    @Override
    public boolean supports(
            MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter type,
            MediaType media,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet) {
            var raw = servlet.getServletRequest();
            if (raw.getRequestURI().startsWith("/v1/")
                    && !raw.getRequestURI().equals("/v1/bootstrap")
                    && !java.util.Set.of("GET", "HEAD").contains(raw.getMethod())
                    && raw.getAttribute(SubjectCookies.IDENTITY_ATTRIBUTE) != null) {
                String token = SubjectCookies.credential(raw);
                subjects.authenticate(token)
                        .ifPresent(
                                identity ->
                                        response.getHeaders()
                                                .add(
                                                        "Set-Cookie",
                                                        SubjectCookies.header(
                                                                token,
                                                                identity.expiresAt(),
                                                                clock.instant(),
                                                                properties)));
            }
        }
        return body;
    }
}
