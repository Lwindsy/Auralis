package io.auralis.catalog.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Shared-schema validation and RFC8785's integer-only subset used by catalog messages. */
public final class CatalogJson {
    public static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, JsonSchema> SCHEMAS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private CatalogJson() {}

    public static void require(String definition, JsonNode value) {
        var schema =
                SCHEMAS.computeIfAbsent(
                        definition,
                        key -> {
                            try (var in =
                                    CatalogJson.class.getResourceAsStream(
                                            "/agent-v1.schema.json")) {
                                var doc = (ObjectNode) JSON.readTree(in);
                                if (!doc.path("$defs").has(key))
                                    throw new IllegalArgumentException("UNKNOWN_DEFINITION");
                                doc.put("$ref", "#/$defs/" + key);
                                return JsonSchemaFactory.getInstance(
                                                SpecVersion.VersionFlag.V202012)
                                        .getSchema(doc);
                            } catch (IOException e) {
                                throw new IllegalStateException("SCHEMA_UNAVAILABLE", e);
                            }
                        });
        if (!schema.validate(value).isEmpty())
            throw new IllegalArgumentException("INVALID_PAYLOAD");
    }

    public static String digest(JsonNode node) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            canonical(node, new DigestOutputStream(OutputStream.nullOutputStream(), md));
            return "sha256:" + HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String contentDigest(JsonNode node, String field) {
        ObjectNode copy = JSON.createObjectNode();
        copy.setAll((ObjectNode) node);
        copy.remove(field);
        return digest(copy);
    }

    private static void raw(OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void canonical(JsonNode node, OutputStream out) throws IOException {
        if (node.isObject()) {
            out.write('{');
            var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            boolean first = true;
            for (String key : keys) {
                if (!first) out.write(',');
                first = false;
                canonical(JSON.getNodeFactory().textNode(key), out);
                out.write(':');
                canonical(node.get(key), out);
            }
            out.write('}');
        } else if (node.isArray()) {
            out.write('[');
            boolean first = true;
            for (var value : node) {
                if (!first) out.write(',');
                first = false;
                canonical(value, out);
            }
            out.write(']');
        } else if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()
                    || node.longValue() < -9007199254740991L
                    || node.longValue() > 9007199254740991L)
                throw new IllegalArgumentException("UNSAFE_INTEGER");
            raw(out, node.asText());
        } else if (node.isTextual()) {
            String value = node.textValue();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                        throw new IllegalArgumentException("INVALID_UNICODE");
                } else if (Character.isLowSurrogate(c))
                    throw new IllegalArgumentException("INVALID_UNICODE");
            }
            raw(out, JSON.writeValueAsString(value));
        } else if (node.isNull() || node.isBoolean()) raw(out, node.toString());
        else throw new IllegalArgumentException("CATALOG_NON_INTEGER_NUMBER");
    }
}
