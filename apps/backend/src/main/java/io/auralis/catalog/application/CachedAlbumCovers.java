package io.auralis.catalog.application;

import io.auralis.catalog.api.AlbumCovers;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Bounded process cache and single-flight fetches; expiry never turns a timeout into absence. */
public final class CachedAlbumCovers implements AlbumCovers, AutoCloseable {
    @FunctionalInterface
    public interface Source {
        Image fetch(UUID album, int size) throws Exception;
    }

    private record Key(UUID album, int size) {}

    private record Entry(Image image, long expires) {}

    private final Source source;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Map<Key, CompletableFuture<Image>> pending = new HashMap<>();
    private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private long bytes;

    public CachedAlbumCovers(Source source, Clock clock) {
        this.source = source;
        this.clock = clock;
        workers =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(32),
                        r -> {
                            var thread = new Thread(r, "album-cover");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    @Override
    public synchronized CompletableFuture<Image> get(UUID album, int size) {
        AlbumCovers.url(album, size);
        var key = new Key(album, size);
        var entry = cache.get(key);
        if (entry != null && entry.expires() > clock.millis())
            return CompletableFuture.completedFuture(entry.image());
        if (entry != null) {
            cache.remove(key);
            bytes -= entry.image().bytes().length;
        }
        if (pending.containsKey(key)) return pending.get(key);
        var future = new CompletableFuture<Image>();
        pending.put(key, future);
        try {
            workers.execute(
                    () -> {
                        Image image;
                        try {
                            image = source.fetch(album, size);
                        } catch (Exception failure) {
                            image = Image.unavailable();
                        }
                        synchronized (this) {
                            long ttl =
                                    image.status() == 200
                                            ? 86_400_000
                                            : image.status() == 404 ? 3_600_000 : 15_000;
                            cache.put(key, new Entry(image, clock.millis() + ttl));
                            bytes += image.bytes().length;
                            while (cache.size() > 256 || bytes > 64L * 1024 * 1024) {
                                var first = cache.entrySet().iterator();
                                bytes -= first.next().getValue().image().bytes().length;
                                first.remove();
                            }
                            pending.remove(key);
                        }
                        future.complete(image);
                    });
        } catch (RejectedExecutionException full) {
            pending.remove(key);
            future.complete(Image.unavailable());
        }
        return future;
    }

    @Override
    public void close() {
        workers.shutdownNow();
        synchronized (this) {
            pending.values().forEach(f -> f.complete(Image.unavailable()));
            pending.clear();
            cache.clear();
            bytes = 0;
        }
    }
}
