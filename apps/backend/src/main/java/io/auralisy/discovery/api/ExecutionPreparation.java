package io.auralisy.discovery.api;

import io.auralisy.session.api.SessionStore.Scope;
import java.util.Set;

/** Refresh mutable display/exclusion state once, before the immutable first send is committed. */
public interface ExecutionPreparation {
    String prepare(Scope scope, String startPayload, Set<String> demands);
}
