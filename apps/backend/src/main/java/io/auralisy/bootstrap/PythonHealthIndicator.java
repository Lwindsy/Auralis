package io.auralisy.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cached by a bounded background probe; browser and health requests never wait on Python. */
@Component("pythonHealthIndicator")
public final class PythonHealthIndicator implements HealthIndicator, AutoCloseable {
    private final OnlineProperties properties;
    private final WireProtocol protocol;
    private final ObjectMapper mapper;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private volatile boolean ready;

    public PythonHealthIndicator(
            OnlineProperties properties, WireProtocol protocol, ObjectMapper mapper) {
        this.properties = properties;
        this.protocol = protocol;
        this.mapper = mapper;
    }

    @PostConstruct
    public void initialize() {
        properties.validate(protocol.revision());
        probe(true);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refresh() {
        probe(false);
    }

    private void probe(boolean starting) {
        boolean nextReady = false;
        try {
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(properties.getPythonUrl() + "/internal/v1/runtime"))
                            .timeout(Duration.ofSeconds(2))
                            .header("Authorization", "Bearer " + properties.getServiceToken())
                            .header("X-Auralis-Audience", "intelligence")
                            .GET()
                            .build();
            // Body cap prevents a misconfigured service from filling memory during a probe.
            var result = exchange(request);
            {
                byte[] bytes = result.body();
                if (result.statusCode() == 401 || result.statusCode() == 403)
                    throw new IllegalArgumentException();
                if (result.statusCode() != 200) return;
                if (bytes.length > 65536) throw new IllegalArgumentException();
                com.fasterxml.jackson.databind.JsonNode message;
                try {
                    message = mapper.readTree(bytes);
                } catch (java.io.IOException failure) {
                    throw new IllegalArgumentException();
                }
                if (!protocol.valid(message)
                        || !"RuntimeView".equals(message.path("message_type").asText()))
                    throw new IllegalArgumentException();
                nextReady = "READY".equals(message.path("payload").path("status").asText());
            }
        } catch (java.io.IOException failure) {
            // Unreachable dependency is readiness DOWN, not proof of supplier failure.
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            if (starting) throw new IllegalStateException("INCOMPATIBLE_PYTHON_PROTOCOL");
        } finally {
            ready = nextReady;
        }
    }

    private HttpResponse<byte[]> exchange(HttpRequest request)
            throws java.io.IOException, InterruptedException {
        var pending = client.sendAsync(request, info -> new LimitedBodySubscriber());
        try {
            return pending.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException failure) {
            throw new java.io.IOException("PYTHON_PROBE_UNAVAILABLE");
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    @Override
    public Health health() {
        return ready ? Health.up().build() : Health.down().build();
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private int size;

        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) size += buffer.remaining();
            if (size > 65536) {
                subscription.cancel();
                delegate.onError(new java.io.IOException("PROBE_RESPONSE_TOO_LARGE"));
            } else delegate.onNext(buffers);
        }

        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }
}
