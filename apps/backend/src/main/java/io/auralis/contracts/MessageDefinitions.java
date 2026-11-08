package io.auralis.contracts;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared schema validation port; callers do not depend on bootstrap or the validator library. */
public interface MessageDefinitions {
    boolean validDefinition(String name, JsonNode value);
}
