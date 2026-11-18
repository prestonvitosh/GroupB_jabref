package org.jabref.logic.shared;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jabref.logic.bibtex.FieldPreferences;
import org.jabref.logic.citationkeypattern.GlobalCitationKeyPatterns;
import org.jabref.logic.exporter.MetaDataSerializer;
import org.jabref.logic.shared.notifications.Notifier;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.BibDatabaseMode;
import org.jabref.model.database.event.EntriesAddedEvent;
import org.jabref.model.database.event.EntriesRemovedEvent;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.event.EntriesEventSource;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;
import org.jabref.model.metadata.event.MetaDataChangeSource;
import org.jabref.model.metadata.event.MetaDataChangedEvent;
import org.jabref.model.util.DummyFileUpdateMonitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.postgresql.jdbc.PgConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OfflineChangesTest {

    @TempDir
    Path directory;

    private DBMSConnectionProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DBMSConnectionPropertiesBuilder()
                .setType(DBMSType.POSTGRESQL)
                .setHost("localhost")
                .setPort(5432)
                .setDatabase("bib")
                .setUser("alice")
                .setPassword("secret")
                .createDBMSConnectionProperties();
    }

    private static BibEntry sharedEntry(int sharedId, int version) {
        BibEntry entry = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "Title " + sharedId);
        entry.getSharedBibEntryData().setSharedId(sharedId);
        entry.getSharedBibEntryData().setVersion(version);
        return entry;
    }

    @Test
    void fileHoldsEveryKindOfRecordAsJson() throws Exception {
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordChange(sharedEntry(1, 3).withField(StandardField.YEAR, "2026"));
        BibEntry newEntry = new BibEntry(StandardEntryType.Book).withField(StandardField.TITLE, "New");
        newEntry.setId("local-id");
        changes.recordInsert(List.of(newEntry));
        changes.recordRemoval(List.of(sharedEntry(2, 5)));
        changes.recordMetaData(Map.of("databaseType", "bibtex"), Map.of("databaseType", "biblatex"));

        String expected = """
                {
                  "changedEntries": {
                    "1": {
                      "baseVersion": 3,
                      "entryType": "article",
                      "fields": {
                        "title": "Title 1",
                        "year": "2026"
                      }
                    }
                  },
                  "newEntries": {
                    "local-id": {
                      "baseVersion": 1,
                      "entryType": "book",
                      "fields": {
                        "title": "New"
                      }
                    }
                  },
                  "removedEntries": {
                    "2": 5
                  },
                  "metaData": {
                    "databaseType": "bibtex"
                  },
                  "metaDataBase": {
                    "databaseType": "biblatex"
                  }
                }""";
        assertEquals(expected, Files.readString(directory.resolve(OfflineChanges.fileName(properties))));
    }

    @Test
    void recordedChangesSurviveReload() {
        BibEntry changed = sharedEntry(1, 3);
        BibEntry removed = sharedEntry(2, 1);
        BibEntry added = new BibEntry(StandardEntryType.Book).withField(StandardField.AUTHOR, "Ada");

        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordChange(changed);
        changes.recordRemoval(List.of(removed));
        changes.recordInsert(List.of(added));
        changes.recordMetaData(Map.of("databaseType", "bibtex;"), Map.of());

        OfflineChanges.Recorded recorded = OfflineChanges.load(directory, properties).peek();

        assertEquals(Map.of(1, new OfflineChanges.EntryState(3, "article", Map.of("title", "Title 1"))), recorded.changedEntries());
        assertEquals(Map.of(added.getId(), new OfflineChanges.EntryState(1, "book", Map.of("author", "Ada"))), recorded.newEntries());
        assertEquals(Map.of(2, 1), recorded.removedEntries());
        assertEquals(Map.of("databaseType", "bibtex;"), recorded.metaData());
        assertEquals(Map.of(), recorded.metaDataBase());
    }

    @Test
    void peekKeepsEverythingUntilItIsForgotten() throws Exception {
        BibEntry changed = sharedEntry(1, 1);
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordChange(changed);
        changes.recordRemoval(List.of(sharedEntry(2, 1)));
        changes.recordMetaData(Map.of("databaseType", "bibtex;"), Map.of());

        changes.peek();

        assertFalse(changes.isEmpty());
        assertFalse(OfflineChanges.load(directory, properties).isEmpty());

        changes.forget(List.of(changed));
        changes.forgetRemovals(Set.of(2));
        changes.forgetMetaData();

        assertTrue(changes.isEmpty());
        assertTrue(OfflineChanges.load(directory, properties).isEmpty());
        assertFalse(Files.exists(directory.resolve(OfflineChanges.fileName(properties))));
    }

    @Test
    void laterChangesKeepTheBaseVersionOfTheFirst() {
        BibEntry entry = sharedEntry(1, 4);
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordChange(entry);
        entry.setField(StandardField.TITLE, "Edited twice");

        changes.recordChange(entry);

        OfflineChanges.EntryState state = changes.peek().changedEntries().get(1);
        assertEquals(4, state.baseVersion());
        assertEquals("Edited twice", state.fields().get("title"));
    }

    @Test
    void removalOfNeverSharedEntryLeavesNoTrace() {
        BibEntry added = new BibEntry(StandardEntryType.Book);
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordInsert(List.of(added));

        changes.recordRemoval(List.of(added));

        assertTrue(changes.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"changedEntries\": null}", "not json at all"})
    void unusableFileLoadsAsEmpty(String content) throws Exception {
        Files.writeString(directory.resolve(OfflineChanges.fileName(properties)), content);

        assertTrue(OfflineChanges.load(directory, properties).isEmpty());
    }

    @Test
    void secondSessionDoesNotOverwriteTheRecordsOfTheFirst() {
        OfflineChanges first = OfflineChanges.load(directory, properties);
        OfflineChanges second = OfflineChanges.load(directory, properties);

        first.recordChange(sharedEntry(1, 1));
        second.recordChange(sharedEntry(2, 1));

        assertEquals(Set.of(1, 2), OfflineChanges.load(directory, properties).peek().changedEntries().keySet());
    }

    @Test
    void differentDatabasesUseDifferentFiles() {
        DBMSConnectionProperties other = new DBMSConnectionPropertiesBuilder()
                .setType(DBMSType.POSTGRESQL)
                .setHost("localhost")
                .setPort(5432)
                .setDatabase("other")
                .setUser("alice")
                .setPassword("secret")
                .createDBMSConnectionProperties();
        OfflineChanges.load(directory, properties).recordChange(sharedEntry(1, 1));

        assertTrue(OfflineChanges.load(directory, other).isEmpty());
    }

    @Test
    void metaDataBaseIsTheOneOfTheFirstRecord() {
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordMetaData(Map.of("protectedFlag", "true;"), Map.of("databaseType", "bibtex;"));

        changes.recordMetaData(Map.of("protectedFlag", "false;"), Map.of("protectedFlag", "true;"));

        OfflineChanges.Recorded recorded = changes.peek();
        assertEquals(Map.of("protectedFlag", "false;"), recorded.metaData());
        assertEquals(Map.of("databaseType", "bibtex;"), recorded.metaDataBase());
    }

    @Test
    void recordedMetaDataMergesKeyByKeyIntoTheSharedOne() {
        Map<String, String> base = Map.of("databaseType", "bibtex;", "protectedFlag", "false;", "saveOrderConfig", "specified;author;false;");
        // Offline: the flag changed, the save order removed
        Map<String, String> recorded = Map.of("databaseType", "bibtex;", "protectedFlag", "true;");
        // Meanwhile on the shared side: a key pattern added, the flag changed as well
        Map<String, String> shared = Map.of("databaseType", "bibtex;", "protectedFlag", "false;", "saveOrderConfig", "specified;author;false;", "keypatterndefault", "[auth];");

        Map<String, String> merged = new OfflineChanges.Recorded(Map.of(), Map.of(), Map.of(), recorded, base).mergeMetaDataInto(shared);

        assertEquals(Map.of("databaseType", "bibtex;", "protectedFlag", "true;", "keypatterndefault", "[auth];"), merged);
    }

    @Test
    void restoredNewEntriesAreRecordedUnderTheirFreshIds() {
        BibEntry recordedInEarlierSession = new BibEntry(StandardEntryType.Book).withField(StandardField.TITLE, "New");
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordInsert(List.of(recordedInEarlierSession));
        BibEntry restored = changes.peek().newEntries().get(recordedInEarlierSession.getId()).toBibEntry();

        changes.rekeyInserts(Map.of(recordedInEarlierSession.getId(), restored));

        assertEquals(Set.of(restored.getId()), changes.peek().newEntries().keySet());
        changes.forget(List.of(restored));
        assertTrue(changes.isEmpty());
    }

    @Test
    void changeOfEntryGoneOnTheSharedSideBecomesNewEntry() {
        BibEntry entry = sharedEntry(1, 2);
        OfflineChanges changes = OfflineChanges.load(directory, properties);
        changes.recordChange(entry);

        changes.recordAsNew(1, entry);

        OfflineChanges.Recorded recorded = changes.peek();
        assertEquals(Map.of(), recorded.changedEntries());
        assertEquals(Set.of(entry.getId()), recorded.newEntries().keySet());
    }

    // [utest->req~shared-database.failed-writes-remain-pending~1]
    @ParameterizedTest
    @ValueSource(strings = {"update", "insert", "remove", "metadata"})
    void failedWriteOnValidConnectionSurvivesRestart(String operation) throws Exception {
        BibDatabaseContext context = new BibDatabaseContext();
        GlobalCitationKeyPatterns patterns = GlobalCitationKeyPatterns.fromPattern("[auth][year]");
        PgConnection sqlConnection = mock(PgConnection.class);
        when(sqlConnection.isValid(0)).thenReturn(true);
        DatabaseConnection connection = mock(DatabaseConnection.class);
        when(connection.getProperties()).thenReturn(properties);
        when(connection.getConnection()).thenReturn(sqlConnection);
        BibEntry entry = sharedEntry(1, 3);
        TaskExecutor taskExecutor = mock(TaskExecutor.class);
        try (MockedConstruction<DBMSProcessor> processors = mockConstruction(DBMSProcessor.class, (processor, _) -> {
            when(processor.checkBaseIntegrity()).thenReturn(true);
        });
             MockedConstruction<Notifier> notifiers = mockConstruction(Notifier.class)) {
            DBMSSynchronizer synchronizer = new DBMSSynchronizer(context, ',', mock(FieldPreferences.class), patterns,
                    new DummyFileUpdateMonitor(), "test", taskExecutor, Runnable::run, Runnable::run, directory);
            synchronizer.openSharedDatabase(connection);
            DBMSProcessor processor = processors.constructed().getFirst();
            SQLException failure = new SQLException("Write failed while connection remains valid");
            switch (operation) {
                case "update" -> {
                    doThrow(failure).when(processor).updateEntry(entry);
                    synchronizer.synchronizeSharedEntry(entry);
                }
                case "insert" -> {
                    doThrow(failure).when(processor).insertEntries(List.of(entry));
                    synchronizer.listen(new EntriesAddedEvent(List.of(entry), EntriesEventSource.LOCAL));
                }
                case "remove" -> {
                    doThrow(failure).when(processor).removeEntries(List.of(entry));
                    synchronizer.listen(new EntriesRemovedEvent(List.of(entry), EntriesEventSource.LOCAL));
                }
                case "metadata" -> {
                    doThrow(failure).when(processor).setSharedMetaData(any());
                    context.getMetaData().setMode(BibDatabaseMode.BIBLATEX);
                    synchronizer.listen(new MetaDataChangedEvent(context.getMetaData(), MetaDataChangeSource.LOCAL));
                }
            }
            verify(sqlConnection).isValid(0);
            verifyNoInteractions(taskExecutor, notifiers.constructed().getFirst());
            OfflineChanges.Recorded recorded = OfflineChanges.load(directory, properties).peek();
            switch (operation) {
                case "update" -> {
                    assertEquals(Map.of(1, OfflineChanges.EntryState.of(entry)), recorded.changedEntries());
                    doNothing().when(processor).updateEntry(entry);
                    synchronizer.synchronizeSharedEntry(entry);
                    assertTrue(OfflineChanges.load(directory, properties).isEmpty());
                }
                case "insert" -> assertEquals(Map.of(entry.getId(), OfflineChanges.EntryState.of(entry)), recorded.newEntries());
                case "remove" -> assertEquals(Map.of(1, 3), recorded.removedEntries());
                case "metadata" -> assertEquals(MetaDataSerializer.getSerializedStringMap(context.getMetaData(), patterns), recorded.metaData());
            }
            synchronizer.closeSharedDatabase();
        }
    }
}
