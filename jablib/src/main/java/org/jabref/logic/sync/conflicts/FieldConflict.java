package org.jabref.logic.sync.conflicts;

import java.util.Optional;

import org.jabref.model.entry.field.Field;

/// A field that was edited to different values on both sides of a three-way merge.
///
/// Each value is empty when the field is absent in that version (for example, deleted on one
/// side, or never present because the merge had no base).
public record FieldConflict(
        Field field,
        Optional<String> baseValue,
        Optional<String> localValue,
        Optional<String> remoteValue) {
}
