package org.jabref.logic.jabdrive;

import org.jabref.logic.JabRefException;

/// The remote library as the synchronization cycle sees it.
///
/// Implementations exchange [SyncDocument] values only, not [org.jabref.model.entry.BibEntry].
public interface SyncTransport {

    PullBatch pull(SyncCheckpoint since) throws JabRefException;

    PushResult push(ChangeSet changeSet) throws JabRefException;
}
