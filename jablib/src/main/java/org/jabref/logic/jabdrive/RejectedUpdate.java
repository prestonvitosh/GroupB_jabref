package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// A pushed change the remote refused because its version is no longer current.
@NullMarked
public record RejectedUpdate(String id, int remoteVersion) {
}
