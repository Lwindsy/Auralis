package io.auralis.bootstrap;

import io.auralis.catalog.adapters.LuceneCatalogSearch;
import io.auralis.catalog.api.*;
import io.auralis.catalog.application.CatalogDirectory;
import io.auralis.contracts.MessageDefinitions;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class CatalogConfiguration {
    @Bean
    CatalogSearchPort catalogSearchPort(
            @Value("${AURALIS_CATALOG_SEARCH_INDEX:}") String path,
            @Value("${AURALIS_CATALOG_SEARCH_MANIFEST_SHA256:}") String manifest)
            throws java.io.IOException {
        if (path.isBlank() && manifest.isBlank())
            return new CatalogSearchPort() {
                public Page search(String query, int offset, int limit) {
                    throw new CatalogFailure(503, "CATALOG_NOT_CONFIGURED");
                }

                public Album resolve(UUID id) {
                    throw new CatalogFailure(503, "CATALOG_NOT_CONFIGURED");
                }

                public String revision() {
                    return "unconfigured";
                }
            };
        // An explicitly configured but invalid index prevents startup; there is no subset fallback.
        return new LuceneCatalogSearch(Path.of(path), manifest);
    }

    @Bean
    CatalogDirectory catalogDirectory(
            CatalogSearchPort search,
            CatalogQuery catalog,
            GenreQuery genres,
            CatalogVersion version,
            MessageDefinitions definitions) {
        return new CatalogDirectory(search, catalog, genres, version, definitions);
    }
}
