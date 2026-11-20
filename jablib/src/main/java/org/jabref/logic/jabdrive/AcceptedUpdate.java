package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// A pushed change the remote stored, with the version the remote assigned.
@NullMarked
public record AcceptedUpdate(String id, int version) {
}
