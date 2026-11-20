package org.jabref.logic.jabdrive;

import java.util.List;

import org.jspecify.annotations.NullMarked;

/// The outcome of one pull-merge-push cycle.
@NullMarked
public record SyncCycleResult(List<SyncConflict> conflicts) {
    public SyncCycleResult {
        conflicts = List.copyOf(conflicts);
    }
}
