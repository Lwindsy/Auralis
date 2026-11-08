package io.auralis.publication.api;

import io.auralis.session.api.SessionStore.Scope;
import java.util.List;
import java.util.UUID;

public interface PublicationStore {
    record Candidate(
            String round,
            String id,
            UUID album,
            int rank,
            String identity,
            String audience,
            String disposition) {}

    record ReadyCandidate(String id, UUID album, int rank) {}

    void addCandidate(Scope scope, Candidate candidate);

    List<ReadyCandidate> candidates(Scope scope, String round, String disposition, int limit);

    void saveCard(
            Scope scope,
            String round,
            String candidate,
            String content,
            String basis,
            String state);

    void saveDelivery(
            Scope scope,
            String id,
            String round,
            String execution,
            long sequence,
            String digest,
            String result);

    boolean putSlot(
            Scope scope,
            String slot,
            String round,
            String candidate,
            String state,
            long expectedRevision);

    boolean putLongReview(
            Scope scope,
            String round,
            String candidate,
            String generation,
            String state,
            String content,
            String reason,
            long expectedRevision);
}
