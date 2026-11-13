package io.auralisy.catalog.api;

import java.util.*;

public interface AudienceQuery {
    /** The full-catalog population is independent of the recall model's training snapshot. */
    record Binding(String dataset, String window, long sampleListeners, String snapshotDigest) {}

    Map<UUID, Long> audience(long catalogDataset, Binding binding, List<UUID> albums);
}
