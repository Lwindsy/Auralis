package io.auralis.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import io.auralis.catalog.api.CatalogFailure;
import io.auralis.catalog.application.CatalogDirectory;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogHttpController {
    private final CatalogDirectory directory;

    public CatalogHttpController(CatalogDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/v1/catalog/albums")
    public JsonNode albums(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return directory.search(query, offset, limit);
    }

    @GetMapping("/v1/catalog/albums/{mbid}")
    public JsonNode album(@PathVariable String mbid) {
        try {
            UUID id = UUID.fromString(mbid);
            if (!id.toString().equalsIgnoreCase(mbid)) throw new IllegalArgumentException();
            return directory.resolve(id);
        } catch (IllegalArgumentException invalid) {
            throw new CatalogFailure(400, "INVALID_PAYLOAD");
        }
    }

    @GetMapping("/v1/genres")
    public JsonNode genres(
            @RequestParam(required = false) String query,
            @RequestParam(name = "parent_id", required = false) String parent,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return directory.genres(query, parent, offset, limit);
    }
}
