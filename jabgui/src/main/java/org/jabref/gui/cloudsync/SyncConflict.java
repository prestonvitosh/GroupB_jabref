package org.jabref.gui.cloudsync;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record SyncConflict(String entryKey, String localVersion, String remoteVersion) {
}
