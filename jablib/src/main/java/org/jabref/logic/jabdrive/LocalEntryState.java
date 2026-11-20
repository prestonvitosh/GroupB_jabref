package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// The local facts [MergePlanner] needs. Field values stay with the entry.
@NullMarked
public record LocalEntryState(int version, boolean dirty, boolean tombstone) {
}
