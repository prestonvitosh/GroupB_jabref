package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// The local version and dirty flag after the user chooses one side of a conflict.
@NullMarked
public record ConflictResolution(int version, boolean dirty) {
}
