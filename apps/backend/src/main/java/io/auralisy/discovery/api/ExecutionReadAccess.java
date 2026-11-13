package io.auralisy.discovery.api;

import io.auralisy.session.api.SessionStore.Scope;

/** Caller first holds the subject/session lock. Retains round and execution locks until commit. */
public interface ExecutionReadAccess {
    void guard(
            Scope scope, String round, String execution, String coordinatorBoot, String pythonBoot);
}
