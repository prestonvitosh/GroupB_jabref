package org.jabref.gui.cloudsync;

import org.jspecify.annotations.NullMarked;

@NullMarked
public enum CloudSyncStatus {
    IDLE,
    SYNCING,
    SYNCED,
    CONFLICT,
    ERROR,
    OFFLINE
}
