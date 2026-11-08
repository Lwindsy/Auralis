package io.auralis.catalog.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import java.io.*;
import java.util.*;

/** Shared-schema validation and RFC8785's integer-only subset used by catalog messages. */
public final class CatalogJson {
    public static final ObjectMapper JSON = io.auralis.contracts.CanonicalJson.JSON;
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
        return io.auralis.contracts.CanonicalJson.digest(node);
    }

    public static String contentDigest(JsonNode node, String field) {
        return io.auralis.contracts.CanonicalJson.contentDigest(node, field);
    }
}
