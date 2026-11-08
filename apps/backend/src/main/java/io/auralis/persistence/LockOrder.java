package io.auralis.persistence;

import java.util.HashSet;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transaction-local verification only; each owning adapter performs its own SQL lock. */
public final class LockOrder {
    public enum Level {
        RUNTIME,
        SUBJECT,
        SESSION,
        ROUND,
        EXECUTION,
        BUDGET,
        INVOCATION,
        DELIVERY,
        SLOT
    }

    private static final Object KEY = new Object();

    private LockOrder() {}

    public static void before(Level level, String id) {
        ShortTransactions.requireWrite();
        var state = (State) TransactionSynchronizationManager.getResource(KEY);
        if (state == null) {
            state = new State();
            TransactionSynchronizationManager.bindResource(KEY, state);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                        }
                    });
        }
        String key = level.name() + ":" + id;
        if (state.held.contains(key)) return;
        if (state.level != null
                && (level.ordinal() < state.level.ordinal()
                        || (level == state.level && id.compareTo(state.id) < 0)))
            throw new PersistenceRejected("LOCK_ORDER_VIOLATION");
        state.held.add(key);
        state.level = level;
        state.id = id;
    }

    private static final class State {
        final Set<String> held = new HashSet<>();
        Level level;
        String id;
    }
}
