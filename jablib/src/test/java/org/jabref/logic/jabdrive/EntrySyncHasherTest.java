package org.jabref.logic.jabdrive;

import java.util.Map;

import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;
import org.jabref.model.metadata.MetaData;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EntrySyncHasherTest {

    private final EntrySyncHasher hasher = new EntrySyncHasher();

    @Test
    void hashIgnoresVersion() {
        BibEntry entry = article("Title");
        entry.getSharedBibEntryData().setVersion(1);
        String first = hasher.hash(entry);
        entry.getSharedBibEntryData().setVersion(9);
        assertEquals(first, hasher.hash(entry));
    }

    @Test
    void hashChangesWhenFieldChanges() {
        BibEntry entry = article("One");
        String before = hasher.hash(entry);
        entry.withField(StandardField.TITLE, "Two");
        assertNotEquals(before, hasher.hash(entry));
    }

    @Test
    void hashIsStableAcrossFieldInsertionOrder() {
        BibEntry titleFirst = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "Title")
                .withField(StandardField.AUTHOR, "Author");
        BibEntry authorFirst = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.AUTHOR, "Author")
                .withField(StandardField.TITLE, "Title");
        assertEquals(hasher.hash(titleFirst), hasher.hash(authorFirst));
    }

    @Test
    void hashIncludesTypeAndCitationKey() {
        BibEntry article = article("Title");
        BibEntry book = new BibEntry(StandardEntryType.Book).withField(StandardField.TITLE, "Title");
        BibEntry keyed = article("Title").withCitationKey("key");
        assertNotEquals(hasher.hash(article), hasher.hash(book));
        assertNotEquals(hasher.hash(article), hasher.hash(keyed));
    }

    @Test
    void hashIgnoresStoredHash() {
        BibEntry entry = article("Title");
        String hash = hasher.hash(entry);
        MetaData metaData = new BibDatabaseContext().getMetaData();
        new SyncMetadata().writeEntries(metaData, Map.of("id", new SyncMetadata.StoredEntry("ffff", 1)));
        assertEquals(hash, hasher.hash(entry));
    }

    private BibEntry article(String title) {
        return new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, title);
    }
}
