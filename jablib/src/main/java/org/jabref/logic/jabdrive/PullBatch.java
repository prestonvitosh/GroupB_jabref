package org.jabref.logic.jabdrive;

import java.util.List;

import org.jspecify.annotations.NullMarked;

/// One page of remote changes and the checkpoint that covers every change in the page.
@NullMarked
public record PullBatch(List<SyncDocument> documents, SyncCheckpoint to) {
    public PullBatch {
        documents = List.copyOf(documents);
    }
}
