package org.jabref.logic.sync.conflicts;

import java.util.List;

import org.jabref.model.entry.BibEntry;

/// The outcome of a three-way merge: the merged entry (with conflicting fields kept at their
/// base value) and the list of fields that need a user decision.
public record MergeResult(BibEntry mergedEntry, List<FieldConflict> conflicts) {
    public MergeResult {
        conflicts = List.copyOf(conflicts);
    }
}
