package io.auralisy.catalog.api;

/** Pins each request to the active, locally admitted catalog version. */
public interface CatalogVersion {
    long current();
}
