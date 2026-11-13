package io.auralisy.persistence;

/**
 * Safe storage conflict code; deliberately distinct from infrastructure/DataAccessException
 * failures.
 */
public final class PersistenceRejected extends RuntimeException {
    public PersistenceRejected(String code) {
        super(code);
    }
}
