package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// A remote change that was not applied because the local entry has unsynchronized edits.
@NullMarked
public record SyncConflict(String sharedId, SyncDocument remote) {
}
