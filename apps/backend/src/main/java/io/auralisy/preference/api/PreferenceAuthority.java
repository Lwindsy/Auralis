package io.auralisy.preference.api;

import io.auralisy.session.api.SessionStore.Scope;
import java.util.*;

/** Internal coordinator port, never a browser-authorized Context deserializer. */
public interface PreferenceAuthority {
    record ExternalSeed(
            String title,
            String artistCredit,
            List<String> mappedGenres,
            String referenceRevision) {
        public ExternalSeed {
            mappedGenres = List.copyOf(mappedGenres);
        }
    }

    /**
     * Expected versions and verified external identities come from Java execution/configuration.
     */
    record Context(
            Scope scope,
            String commandId,
            long catalogDataset,
            String referenceRevision,
            Set<String> parserRevisions,
            Map<String, String> genreNames,
            Map<String, ExternalSeed> externalSeeds) {
        public Context {
            parserRevisions = Set.copyOf(parserRevisions);
            genreNames = Map.copyOf(genreNames);
            externalSeeds = Map.copyOf(externalSeeds);
        }
    }

    /** Reads the canonical input from the active stored session, not from Python's request. */
    String resolve(Context context, String proposal);
}
