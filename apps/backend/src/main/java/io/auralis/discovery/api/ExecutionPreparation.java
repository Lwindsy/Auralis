package io.auralis.discovery.api;

import io.auralis.session.api.SessionStore.Scope;
import java.util.Set;

/** Refresh mutable display/exclusion state once, before the immutable first send is committed. */
public interface ExecutionPreparation {
    String prepare(Scope scope, String startPayload, Set<String> demands);
}
