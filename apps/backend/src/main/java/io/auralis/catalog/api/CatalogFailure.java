package io.auralis.catalog.api;

/** Safe public code only; upstream bodies and user queries never enter error messages. */
public final class CatalogFailure extends RuntimeException {
    private final int status;

    public CatalogFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
