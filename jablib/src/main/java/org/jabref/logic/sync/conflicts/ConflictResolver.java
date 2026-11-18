package org.jabref.logic.sync.conflicts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;

import org.jspecify.annotations.Nullable;

/// Performs a field-by-field three-way merge of a [BibEntry], using the last synced version
/// as the base.
public class ConflictResolver {

    /// Merges `local` and `remote` against the common `base`.
    ///
    /// @param base the last synced version, or `null` if the entry was never synced
    /// @param local the locally edited version
    /// @param remote the remotely edited version
    /// @return the merged entry together with all field conflicts
    /// @throws NullPointerException if `local` or `remote` is `null`
    public MergeResult merge(@Nullable BibEntry base, BibEntry local, BibEntry remote) {
        // Dereferencing local and remote first gives the NullPointerException required for
        // null arguments before any merge work happens.
        Map<Field, String> localFields = local.getFieldMap();
        Map<Field, String> remoteFields = remote.getFieldMap();
        Map<Field, String> baseFields = Optional.ofNullable(base)
                                                .map(BibEntry::getFieldMap)
                                                .orElseGet(Map::of);

        SequencedSet<Field> allFields = new LinkedHashSet<>(baseFields.keySet());
        allFields.addAll(localFields.keySet());
        allFields.addAll(remoteFields.keySet());

        BibEntry merged = new BibEntry(local.getType());
        List<FieldConflict> conflicts = new ArrayList<>();
        for (Field field : allFields) {
            Optional<String> baseValue = Optional.ofNullable(baseFields.get(field));
            Optional<String> localValue = Optional.ofNullable(localFields.get(field));
            Optional<String> remoteValue = Optional.ofNullable(remoteFields.get(field));

            if (localValue.equals(remoteValue)) {
                // Unchanged on both sides, or both sides made the same change (rule 3).
                localValue.ifPresent(value -> merged.setField(field, value));
            } else if (baseValue.equals(localValue)) {
                // Changed (or deleted) only remotely (rules 2 and 5).
                remoteValue.ifPresent(value -> merged.setField(field, value));
            } else if (baseValue.equals(remoteValue)) {
                // Changed (or deleted) only locally (rules 1 and 5).
                localValue.ifPresent(value -> merged.setField(field, value));
            } else {
                // Both sides changed the field to different values, which includes a deletion
                // against an edit and a null base where the sides added different values: the
                // conflict is reported and the merged entry keeps the base value (rules 4-6).
                conflicts.add(new FieldConflict(field, baseValue, localValue, remoteValue));
                baseValue.ifPresent(value -> merged.setField(field, value));
            }
        }
        return new MergeResult(merged, conflicts);
    }

    /// Applies the chosen value for each conflicting field of `result` and returns the final
    /// entry.
    ///
    /// @throws IllegalArgumentException if any conflict has no choice, or a choice refers to
    /// a field that was not in conflict
    public BibEntry resolve(MergeResult result, Map<Field, String> choices) {
        Set<Field> conflictingFields = result.conflicts().stream()
                                             .map(FieldConflict::field)
                                             .collect(Collectors.toUnmodifiableSet());
        if (!conflictingFields.equals(choices.keySet())) {
            throw new IllegalArgumentException(
                    "Choices must cover exactly the conflicting fields %s, but were given for %s"
                            .formatted(conflictingFields, choices.keySet()));
        }

        BibEntry resolved = new BibEntry(result.mergedEntry());
        choices.forEach(resolved::setField);
        return resolved;
    }
}
