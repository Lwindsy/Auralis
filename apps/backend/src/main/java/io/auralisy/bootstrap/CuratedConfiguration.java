package io.auralisy.bootstrap;

import io.auralisy.catalog.api.CatalogVersion;
import io.auralisy.catalog.api.GenreQuery;
import io.auralisy.catalog.application.CatalogDirectory;
import io.auralisy.discovery.adapters.CuratedContentFiles;
import io.auralisy.discovery.adapters.PreparedCuratedRecommendations;
import io.auralisy.discovery.api.CuratedRecommendations;
import io.auralisy.persistence.PersistenceRejected;
import io.auralisy.publication.api.PublicationAuthority;
import io.auralisy.session.api.SessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class CuratedConfiguration {
    @Bean
    @ConditionalOnMissingBean(CuratedRecommendations.class)
    CuratedRecommendations curatedList(
            Environment environment,
            CatalogDirectory directory,
            CatalogVersion versions,
            GenreQuery genres,
            SessionStore sessions,
            PublicationAuthority publication)
            throws java.io.IOException {
        String path = environment.getProperty("AURALIS_CURATED_CONTENT_DIRECTORY");
        if (path != null && !path.isBlank())
            return new PreparedCuratedRecommendations(
                    CuratedContentFiles.load(java.nio.file.Path.of(path)),
                    directory,
                    versions,
                    genres,
                    sessions,
                    publication);
        return (scope, round, preference) -> {
            throw new PersistenceRejected("CURATED_UNAVAILABLE");
        };
    }
}
