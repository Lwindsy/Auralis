package io.auralisy.preference.application;

import static io.auralisy.contracts.CanonicalJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralisy.preference.api.PreferenceRejected;
import java.util.*;

/**
 * Load once from the same approved reference document as Python, with an independent digest pin.
 */
public record PreferenceReference(String revision, Map<String, String> names) {
    public PreferenceReference {
        names = Map.copyOf(names);
    }

    public static PreferenceReference verified(JsonNode document, String expectedDigest) {
        if (!digest(document).equals(expectedDigest)
                || !document.path("genres").isArray()
                || !document.path("branches").isArray())
            throw new PreferenceRejected("REFERENCE_VERSION_MISMATCH");
        var names = new LinkedHashMap<String, String>();
        for (var genre : document.path("genres")) {
            String id = genre.path("id").asText(), name = genre.path("name").asText();
            if (id.isBlank() || name.isBlank() || names.putIfAbsent(id, name) != null)
                throw new PreferenceRejected("REFERENCE_UNAVAILABLE");
        }
        for (var branch : document.path("branches"))
            for (var id : branch.path("genre_ids"))
                if (!names.containsKey(id.asText()))
                    throw new PreferenceRejected("REFERENCE_UNAVAILABLE");
        return new PreferenceReference(expectedDigest, names);
    }
}
