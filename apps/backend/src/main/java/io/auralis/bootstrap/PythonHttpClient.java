package io.auralis.bootstrap;

import static io.auralis.contracts.CanonicalJson.JSON;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.discovery.api.PythonClient;
import io.auralis.persistence.ShortTransactions;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public final class PythonHttpClient implements PythonClient, AutoCloseable {
    private final OnlineProperties properties;
    private final WireProtocol wire;
    private final Semaphore requests = new Semaphore(3);
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public PythonHttpClient(OnlineProperties properties, WireProtocol wire) {
        this.properties = properties;
        this.wire = wire;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public String runtime() {
        return exchange("GET", "/runtime", null, null, "RuntimeView");
    }

    public String start(String id, String payload) {
        return exchange("PUT", "/executions/" + encode(id), "RoundStart", payload, "ExecutionView");
    }

    public String admission(String id, String boot, String digest, String deadline) {
        return exchange(
                "GET",
                "/executions/"
                        + encode(id)
                        + "/admission?python_boot_id="
                        + encode(boot)
                        + "&payload_digest="
                        + encode(digest)
                        + "&original_deadline="
                        + encode(deadline),
                null,
                null,
                "ExecutionAdmission");
    }

    public String cancel(String id, String coordinator, String python, String reason) {
        var p = JSON.createObjectNode().put("reason", reason);
        p.putObject("boot").put("coordinator_boot_id", coordinator).put("python_boot_id", python);
        return exchange(
                "PUT",
                "/executions/" + encode(id) + "/cancellation",
                "Cancellation",
                p.toString(),
                "ExecutionView");
    }

    private String exchange(
            String method, String path, String kind, String payload, String expected) {
        return ShortTransactions.outsideTransaction(
                () -> {
                    if (!requests.tryAcquire()) throw new Unavailable();
                    CompletableFuture<HttpResponse<byte[]>> pending = null;
                    try {
                        String requestId = UUID.randomUUID().toString();
                        var request =
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        properties.getPythonUrl()
                                                                + "/internal/v1"
                                                                + path))
                                        .timeout(Duration.ofSeconds(3))
                                        .header(
                                                "Authorization",
                                                "Bearer " + properties.getServiceToken())
                                        .header("X-Auralis-Audience", "intelligence");
                        if (payload == null) request.GET();
                        else {
                            JsonNode p = JSON.readTree(payload);
                            var envelope =
                                    JSON.createObjectNode()
                                            .put("schema_version", "intel.v1")
                                            .put("contract_revision", wire.revision())
                                            .put("request_id", requestId)
                                            .put("message_type", kind);
                            envelope.putObject("trace_context")
                                    .put("trace_id", requestId)
                                    .put("span_id", requestId.substring(0, 16));
                            envelope.set("payload", p);
                            if (!wire.valid(envelope)) throw new Unavailable();
                            request.header("Content-Type", "application/json")
                                    .method(
                                            method,
                                            HttpRequest.BodyPublishers.ofByteArray(
                                                    JSON.writeValueAsBytes(envelope)));
                        }
                        pending = client.sendAsync(request.build(), info -> new BoundedBody());
                        var response = pending.get(3, TimeUnit.SECONDS);
                        var message = JSON.readTree(response.body());
                        if (response.statusCode() < 200
                                || response.statusCode() >= 300
                                || !wire.valid(message)
                                || !expected.equals(message.path("message_type").asText())
                                || (payload != null
                                        && !requestId.equals(message.path("request_id").asText())))
                            throw new Unavailable();
                        return message.path("payload").toString();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Unavailable();
                    } catch (Exception e) {
                        throw new Unavailable();
                    } finally {
                        if (pending != null && !pending.isDone()) pending.cancel(true);
                        requests.release();
                    }
                });
    }

    public void close() {
        client.shutdownNow();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int size;

        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            delegate.onSubscribe(s);
        }

        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                size += chunk.remaining();
                if (size > 4 * 1024 * 1024) {
                    subscription.cancel();
                    delegate.onError(new IllegalStateException("BODY_TOO_LARGE"));
                    return;
                }
            }
            delegate.onNext(chunks);
        }

        public void onError(Throwable error) {
            delegate.onError(error);
        }

        public void onComplete() {
            delegate.onComplete();
        }
    }

    @Override
    public String command(String execution, String command, String payload) {
        return exchange(
                "PUT",
                "/executions/" + encode(execution) + "/commands/" + encode(command),
                "RoundCommand",
                payload,
                "Acknowledgement");
    }
}
