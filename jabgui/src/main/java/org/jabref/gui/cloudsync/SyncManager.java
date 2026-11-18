package org.jabref.gui.cloudsync;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface SyncManager {

    SyncResult sync() throws Exception;

    int getPendingChangesCount();
}
