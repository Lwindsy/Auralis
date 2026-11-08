package io.auralis.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Component;

/** Loads the packaged authoritative Python-owned schema; no copied Java DTO definition. */
@Component
public final class WireProtocol {
    private final JsonSchema schema;
    private final JsonSchema productErrorSchema;
    private final String revision;

    public WireProtocol(ObjectMapper mapper) throws Exception {
        try (var in = getClass().getResourceAsStream("/agent-v1.schema.json")) {
            JsonNode document = mapper.readTree(in);
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
}
