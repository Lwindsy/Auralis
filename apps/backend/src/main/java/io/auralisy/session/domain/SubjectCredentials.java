package io.auralisy.session.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Opaque signed credentials; no input or identity is encoded into browser cookies. */
public final class SubjectCredentials {
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public SubjectCredentials(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43,256}"))
            throw new IllegalArgumentException("INVALID_SUBJECT_SECRET");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return value + "." + mac("credential:" + value);
    }

    public boolean valid(String token) {
        return token != null
                && token.matches("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")
                && same(mac("credential:" + token.substring(0, 43)), token.substring(44));
    }

    public String csrf(String token) {
        return mac("csrf:" + token);
    }

    public String generation() {
        return digest("generation:" + Base64.getEncoder().encodeToString(secret)).substring(7);
    }

    public static boolean same(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(
                        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(String text) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("DIGEST_UNAVAILABLE");
        }
    }

    private String mac(String text) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SIGNER_UNAVAILABLE");
        }
    }
}
