package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// Logical time of the last remote change integrated into the local library.
///
/// `serverTime` and `highestId` together identify a position in the remote change feed.
/// Callers send the pair back as the next pull's `since` and do not interpret it.
@NullMarked
public record SyncCheckpoint(long serverTime, String highestId) {
    public static final SyncCheckpoint EMPTY = new SyncCheckpoint(0L, "");
}
