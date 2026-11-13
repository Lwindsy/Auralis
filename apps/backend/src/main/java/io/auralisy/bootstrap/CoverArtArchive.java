package io.auralisy.bootstrap;

import io.auralisy.catalog.api.AlbumCovers.Image;
import io.auralisy.catalog.application.CachedAlbumCovers;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed CAA source; only CAA/Internet Archive HTTPS redirects are followed. */
public final class CoverArtArchive implements CachedAlbumCovers.Source {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final URI base;

    public CoverArtArchive() {
        this(URI.create("https://coverartarchive.org/"));
    }

    CoverArtArchive(URI base) {
        this.base = base;
    }

    @Override
    public Image fetch(UUID album, int size) throws Exception {
        URI url = base.resolve("release-group/" + album + "/front-" + size);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        for (int redirect = 0; redirect <= 4; redirect++) {
            if (!allowed(url)) return Image.unavailable();
            var connection = (HttpURLConnection) url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.min(3000, remaining(deadline)));
            connection.setReadTimeout(Math.min(5000, remaining(deadline)));
            connection.setRequestProperty(
                    "User-Agent", "Auralisy/0.1 (https://github.com/Lwindsy/Auralis)");
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp");
            try {
                int status = connection.getResponseCode();
                if (status == 404) return Image.missing();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return Image.unavailable();
                    url = url.resolve(location);
                    continue;
                }
                if (status != 200) return Image.unavailable();
                String contentType =
                        Objects.toString(connection.getContentType(), "")
                                .split(";", 2)[0]
                                .strip()
                                .toLowerCase(Locale.ROOT);
                if (!Set.of("image/jpeg", "image/png", "image/webp").contains(contentType)
                        || connection.getContentLengthLong() > MAX_BYTES)
                    return Image.unavailable();
                try (var input = connection.getInputStream();
                        var output = new ByteArrayOutputStream()) {
                    var buffer = new byte[8192];
                    while (true) {
                        connection.setReadTimeout(Math.min(5000, remaining(deadline)));
                        int count = input.read(buffer);
                        if (count < 0) break;
                        if (output.size() + count > MAX_BYTES) return Image.unavailable();
                        output.write(buffer, 0, count);
                    }
                    return output.size() == 0
                            ? Image.unavailable()
                            : new Image(200, contentType, output.toByteArray());
                }
            } finally {
                connection.disconnect();
            }
        }
        return Image.unavailable();
    }

    private boolean allowed(URI url) {
        if (url.getUserInfo() != null || url.getFragment() != null) return false;
        // An injected loopback source is used only by in-process tests.
        if (url.getScheme().equals(base.getScheme())
                && Objects.equals(url.getHost(), base.getHost())
                && url.getPort() == base.getPort()) return true;
        String host = Objects.toString(url.getHost(), "").toLowerCase(Locale.ROOT);
        return "https".equals(url.getScheme())
                && (url.getPort() == -1 || url.getPort() == 443)
                && (host.equals("coverartarchive.org")
                        || host.equals("archive.org")
                        || host.endsWith(".archive.org"));
    }

    private static int remaining(long deadline) throws java.net.SocketTimeoutException {
        long ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (ms <= 0) throw new java.net.SocketTimeoutException("COVER_TIMEOUT");
        return (int) ms;
    }
}
