package io.auralis.contracts;

import java.net.URI;

/** Pure syntax check; performs no network or DNS operations. */
public final class HttpsAddress {
    private HttpsAddress() {}

    public static boolean valid(String text) {
        try {
            var uri = URI.create(text);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (Exception e) {
            return false;
        }
    }
}
