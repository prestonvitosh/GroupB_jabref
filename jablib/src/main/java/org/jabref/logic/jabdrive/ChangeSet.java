package org.jabref.logic.jabdrive;

import java.util.List;

import org.jspecify.annotations.NullMarked;

/// Local changes waiting to be pushed: edits of entries the remote already knows, entries that
/// have no remote id yet, and deletions.
@NullMarked
public record ChangeSet(List<SyncDocument> dirtyUpdates, List<SyncDocument> newEntries, List<SyncDocument> tombstones) {
    public ChangeSet {
        dirtyUpdates = List.copyOf(dirtyUpdates);
        newEntries = List.copyOf(newEntries);
        tombstones = List.copyOf(tombstones);
    }

    public boolean isEmpty() {
        return dirtyUpdates.isEmpty() && newEntries.isEmpty() && tombstones.isEmpty();
    }
}
