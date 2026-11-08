package io.auralis.persistence;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit composition boundary; callbacks contain database work only and are never retried here.
 */
@Component
public final class ShortTransactions {
    private final PlatformTransactionManager manager;

    public ShortTransactions(PlatformTransactionManager manager) {
        this.manager = manager;
    }

    public <T> T write(Supplier<T> action) {
        return execute(false, action);
    }

    public <T> T read(Supplier<T> action) {
        return execute(true, action);
    }

    private <T> T execute(boolean readOnly, Supplier<T> action) {
        var template = new TransactionTemplate(manager);
        template.setTimeout(5);
        template.setReadOnly(readOnly);
        return template.execute(status -> action.get());
    }

    public static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new PersistenceRejected("WRITE_TRANSACTION_REQUIRED");
    }

    public static <T> T outsideTransaction(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new PersistenceRejected("EXTERNAL_IO_INSIDE_TRANSACTION");
        return action.get();
    }
}
