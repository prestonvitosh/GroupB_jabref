package org.jabref.logic.sync.conflicts;

import java.util.Map;
import java.util.Optional;

import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictResolverTest {

    private final ConflictResolver resolver = new ConflictResolver();

    private static BibEntry articleEntry() {
        return new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "Base Title")
                .withField(StandardField.AUTHOR, "Base Author");
    }

    private static FieldConflict conflictFor(MergeResult result, Field field) {
        return result.conflicts().stream()
                     .filter(conflict -> conflict.field().equals(field))
                     .findFirst()
                     .orElseThrow();
    }

    // Rule 1: a field changed only locally is merged with the local value
    @Test
    void fieldChangedOnlyLocallyUsesLocalValue() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry().withField(StandardField.TITLE, "Local Title");
        BibEntry remote = articleEntry();

        MergeResult result = resolver.merge(base, local, remote);

        assertEquals(Optional.of("Local Title"), result.mergedEntry().getField(StandardField.TITLE));
        assertEquals(Optional.of("Base Author"), result.mergedEntry().getField(StandardField.AUTHOR));
        assertTrue(result.conflicts().isEmpty());
    }

    // Rule 2: a field changed only remotely is merged with the remote value
    @Test
    void fieldChangedOnlyRemotelyUsesRemoteValue() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry();
        BibEntry remote = articleEntry().withField(StandardField.TITLE, "Remote Title");

        MergeResult result = resolver.merge(base, local, remote);

        assertEquals(Optional.of("Remote Title"), result.mergedEntry().getField(StandardField.TITLE));
        assertEquals(Optional.of("Base Author"), result.mergedEntry().getField(StandardField.AUTHOR));
        assertTrue(result.conflicts().isEmpty());
    }

    // Rule 3: both sides changed a field to the same value, so that value is used without a conflict
    @Test
    void bothSidesSameChangeIsNotAConflict() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry().withField(StandardField.TITLE, "Agreed Title");
        BibEntry remote = articleEntry().withField(StandardField.TITLE, "Agreed Title");

        MergeResult result = resolver.merge(base, local, remote);

        assertEquals(Optional.of("Agreed Title"), result.mergedEntry().getField(StandardField.TITLE));
        assertTrue(result.conflicts().isEmpty());
    }

    // Rule 4: both sides changed a field to different values, so a conflict is reported
    // and the merged entry keeps the base value
    @Test
    void bothSidesDifferentChangeReportsConflictAndKeepsBaseValue() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry().withField(StandardField.TITLE, "Local Title");
        BibEntry remote = articleEntry().withField(StandardField.TITLE, "Remote Title");

        MergeResult result = resolver.merge(base, local, remote);

        assertEquals(1, result.conflicts().size());
        FieldConflict conflict = conflictFor(result, StandardField.TITLE);
        assertEquals(Optional.of("Base Title"), conflict.baseValue());
        assertEquals(Optional.of("Local Title"), conflict.localValue());
        assertEquals(Optional.of("Remote Title"), conflict.remoteValue());
        assertEquals(Optional.of("Base Title"), result.mergedEntry().getField(StandardField.TITLE));
    }

    // Rule 5: a field deleted on one side and unchanged on the other is deleted in the merged
    // entry; a field deleted on one side and edited on the other is a conflict
    @Test
    void deletionOnOneSideIsMergedAndDeletionAgainstEditIsConflict() {
        BibEntry base = articleEntry();
        BibEntry localDeleted = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.AUTHOR, "Base Author");
        BibEntry remoteUnchanged = articleEntry();

        MergeResult deletionResult = resolver.merge(base, localDeleted, remoteUnchanged);

        assertEquals(Optional.empty(), deletionResult.mergedEntry().getField(StandardField.TITLE));
        assertTrue(deletionResult.conflicts().isEmpty());

        BibEntry remoteEdited = articleEntry().withField(StandardField.TITLE, "Remote Title");

        MergeResult conflictResult = resolver.merge(base, localDeleted, remoteEdited);

        assertEquals(1, conflictResult.conflicts().size());
        FieldConflict conflict = conflictFor(conflictResult, StandardField.TITLE);
        assertEquals(Optional.of("Base Title"), conflict.baseValue());
        assertEquals(Optional.empty(), conflict.localValue());
        assertEquals(Optional.of("Remote Title"), conflict.remoteValue());
    }

    // Rule 6: a null base treats every field as newly added; fields that differ between
    // local and remote are conflicts
    @Test
    void nullBaseTreatsFieldsAsNewlyAdded() {
        BibEntry local = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "Shared Title")
                .withField(StandardField.AUTHOR, "Local Author");
        BibEntry remote = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "Shared Title")
                .withField(StandardField.AUTHOR, "Remote Author");

        MergeResult result = resolver.merge(null, local, remote);

        assertEquals(Optional.of("Shared Title"), result.mergedEntry().getField(StandardField.TITLE));
        assertEquals(1, result.conflicts().size());
        FieldConflict conflict = conflictFor(result, StandardField.AUTHOR);
        assertEquals(Optional.empty(), conflict.baseValue());
        assertEquals(Optional.of("Local Author"), conflict.localValue());
        assertEquals(Optional.of("Remote Author"), conflict.remoteValue());
    }

    // Rule 7: a null local or remote entry causes a NullPointerException
    @Test
    void nullLocalOrRemoteThrowsNullPointerException() {
        BibEntry base = articleEntry();

        assertThrows(NullPointerException.class, () -> resolver.merge(base, null, articleEntry()));
        assertThrows(NullPointerException.class, () -> resolver.merge(base, articleEntry(), null));
    }

    // Rule 8: merge never modifies the base, local, or remote entries passed in
    @Test
    void mergeDoesNotModifyInputEntries() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry().withField(StandardField.TITLE, "Local Title");
        BibEntry remote = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "Remote Title");
        Map<Field, String> baseFields = Map.copyOf(base.getFieldMap());
        Map<Field, String> localFields = Map.copyOf(local.getFieldMap());
        Map<Field, String> remoteFields = Map.copyOf(remote.getFieldMap());

        resolver.merge(base, local, remote);

        assertEquals(baseFields, base.getFieldMap());
        assertEquals(localFields, local.getFieldMap());
        assertEquals(remoteFields, remote.getFieldMap());
    }

    // Rule 9: resolve applies the chosen value for each conflicting field
    @Test
    void resolveAppliesChosenValuesToConflictingFields() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry()
                .withField(StandardField.TITLE, "Local Title")
                .withField(StandardField.YEAR, "2026");
        BibEntry remote = articleEntry()
                .withField(StandardField.TITLE, "Remote Title")
                .withField(StandardField.AUTHOR, "Remote Author");

        MergeResult result = resolver.merge(base, local, remote);
        BibEntry resolved = resolver.resolve(result, Map.of(StandardField.TITLE, "Remote Title"));

        assertEquals(Optional.of("Remote Title"), resolved.getField(StandardField.TITLE));
        assertEquals(Optional.of("Remote Author"), resolved.getField(StandardField.AUTHOR));
        assertEquals(Optional.of("2026"), resolved.getField(StandardField.YEAR));
    }

    // Rule 10: resolve throws IllegalArgumentException when a conflict has no choice or a
    // choice refers to a field that was not in conflict
    @Test
    void resolveThrowsWhenChoiceIsMissingOrUnknown() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry().withField(StandardField.TITLE, "Local Title");
        BibEntry remote = articleEntry().withField(StandardField.TITLE, "Remote Title");

        MergeResult result = resolver.merge(base, local, remote);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(result, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(result,
                Map.of(StandardField.TITLE, "Local Title", StandardField.AUTHOR, "Not In Conflict")));
    }

    // Edge case: identical entries produce no conflicts and an unchanged merged entry
    @Test
    void identicalEntriesProduceNoConflicts() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry();
        BibEntry remote = articleEntry();

        MergeResult result = resolver.merge(base, local, remote);

        assertTrue(result.conflicts().isEmpty());
        assertEquals(base.getFieldMap(), result.mergedEntry().getFieldMap());
    }

    // Edge case: multiple conflicting fields in one entry are all reported
    @Test
    void multipleConflictingFieldsAreAllReported() {
        BibEntry base = articleEntry();
        BibEntry local = articleEntry()
                .withField(StandardField.TITLE, "Local Title")
                .withField(StandardField.AUTHOR, "Local Author");
        BibEntry remote = articleEntry()
                .withField(StandardField.TITLE, "Remote Title")
                .withField(StandardField.AUTHOR, "Remote Author");

        MergeResult result = resolver.merge(base, local, remote);

        assertEquals(2, result.conflicts().size());
        assertEquals(Optional.of("Local Title"), conflictFor(result, StandardField.TITLE).localValue());
        assertEquals(Optional.of("Remote Author"), conflictFor(result, StandardField.AUTHOR).remoteValue());
    }
}
