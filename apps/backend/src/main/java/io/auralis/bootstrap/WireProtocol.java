package io.auralis.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Component;

/** Loads the packaged authoritative Python-owned schema; no copied Java DTO definition. */
@Component
public final class WireProtocol implements io.auralis.contracts.MessageDefinitions {
    private final JsonSchema schema;
    private final JsonSchema productErrorSchema;
    private final String revision;
    private final com.fasterxml.jackson.databind.node.ObjectNode document;
    private final java.util.Map<String, JsonSchema> definitions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public WireProtocol(ObjectMapper mapper) throws Exception {
        try (var in = getClass().getResourceAsStream("/agent-v1.schema.json")) {
            document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(in);
            revision =
                    document.path("$defs")
                            .path("Envelope")
                            .path("oneOf")
                            .get(0)
                            .path("properties")
                            .path("contract_revision")
                            .path("const")
                            .asText();
            schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(document);
            var product = ((com.fasterxml.jackson.databind.node.ObjectNode) document).deepCopy();
            product.put("$ref", "#/$defs/ProductError");
            productErrorSchema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(product);
        }
    }

    public String revision() {
        return revision;
    }

    public boolean valid(JsonNode message) {
        return schema.validate(message).isEmpty();
    }

    public boolean validProductError(JsonNode message) {
        return productErrorSchema.validate(message).isEmpty();
    }

    public boolean validDefinition(String name, JsonNode value) {
        if (!document.path("$defs").has(name))
            throw new IllegalArgumentException("UNKNOWN_DEFINITION");
        return definitions
                .computeIfAbsent(
                        name,
                        key -> {
                            var selected = document.deepCopy();
                            selected.put("$ref", "#/$defs/" + key);
                            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                                    .getSchema(selected);
                        })
                .validate(value)
                .isEmpty();
    }
}
