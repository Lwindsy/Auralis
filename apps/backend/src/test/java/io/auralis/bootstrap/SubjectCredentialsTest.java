package io.auralis.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.auralis.session.domain.SubjectCredentials;
import org.junit.jupiter.api.Test;

class SubjectCredentialsTest {
    @Test
    void signedOpaqueTokensAndCsrfCannotBeForgedOrReusedAcrossSubjects() {
        var keys =
                new SubjectCredentials("test-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        String first = keys.issue(), second = keys.issue();
        assertNotEquals(first, second);
        assertTrue(keys.valid(first));
        assertFalse(keys.valid(first.substring(0, 86) + (first.endsWith("a") ? "b" : "a")));
        assertFalse(keys.valid("attacker_supplied_subject_id"));
        assertFalse(SubjectCredentials.same(keys.csrf(first), keys.csrf(second)));
        assertTrue(SubjectCredentials.digest(first).matches("sha256:[0-9a-f]{64}"));
        var rotated =
                new SubjectCredentials(
                        "rotated-subject-secret-abcdefghijklmnopqrstuvwxyz0123456789");
        assertFalse(rotated.valid(first));
        assertNotEquals(rotated.generation(), keys.generation());
        assertThrows(IllegalArgumentException.class, () -> new SubjectCredentials("short"));
    }

    @Test
    void httpCookiesRequireAnExplicitLocalDevelopmentException() {
        var properties = OnlineBootstrapTest.properties();
        properties.setAllowInsecureCookie(false);
        assertThrows(IllegalArgumentException.class, () -> properties.validate("0.26"));
        properties.setProductOrigin("https://auralis.example");
        properties.validate("0.26");
        String header =
                SubjectCookies.header(
                        "opaque",
                        java.time.Instant.now().plusSeconds(30),
                        java.time.Instant.now(),
                        properties);
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Lax"));
    }
}
