package io.auralisy.bootstrap;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

public final class SubjectCookies {
    public static final String NAME = "auralis_session";
    public static final String IDENTITY_ATTRIBUTE = "auralis.authenticated_subject";

    private SubjectCookies() {}

    public static String credential(HttpServletRequest request) {
        String result = null;
        if (request.getCookies() != null)
            for (var cookie : request.getCookies())
                if (NAME.equals(cookie.getName())) {
                    if (result != null) throw new IllegalArgumentException("AMBIGUOUS_CREDENTIAL");
                    result = cookie.getValue();
                }
        return result;
    }

    public static String header(
            String token, Instant expires, Instant now, OnlineProperties properties) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(properties.getProductOrigin().startsWith("https://"))
                .sameSite("Lax")
                .path("/")
                .maxAge(Math.max(0, Duration.between(now, expires).getSeconds()))
                .build()
                .toString();
    }
}
