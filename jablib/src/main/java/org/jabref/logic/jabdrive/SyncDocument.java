package org.jabref.logic.jabdrive;

import java.util.Map;

import org.jspecify.annotations.NullMarked;

/// One entry, or the tombstone of a deleted entry, as it crosses the transport boundary.
@NullMarked
public record SyncDocument(
        String id,
        int version,
        String contentHash,
        String entryType,
        String citationKey,
        Map<String, String> fields,
        boolean tombstone) {
    public SyncDocument {
        fields = Map.copyOf(fields);
    }
}
