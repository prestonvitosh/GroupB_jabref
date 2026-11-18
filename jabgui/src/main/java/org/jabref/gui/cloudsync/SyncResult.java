package org.jabref.gui.cloudsync;

import java.util.List;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record SyncResult(List<SyncConflict> conflicts) {
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }
}
