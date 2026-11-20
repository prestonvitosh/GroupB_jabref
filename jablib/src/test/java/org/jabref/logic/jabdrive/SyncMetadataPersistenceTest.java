package org.jabref.logic.jabdrive;

import java.util.List;
import java.util.Map;

import org.jabref.logic.util.CurrentThreadTaskExecutor;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncMetadataPersistenceTest {

    private final EntrySyncHasher hasher = new EntrySyncHasher();
    private final MergePlanner planner = new MergePlanner();
    private final SyncMetadata metadata = new SyncMetadata();

    @Test
    void checkpointRoundTripsThroughMetaData() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport(1);
        transport.insert(document("a", 1, "A"));
        transport.insert(document("b", 1, "B"));
        PullBatch first = transport.pull(SyncCheckpoint.EMPTY);
        BibDatabaseContext context = new BibDatabaseContext();
        metadata.writeCheckpoint(context.getMetaData(), first.to());
        SynchronizationManager manager = manager(context, transport);

        manager.synchronize();

        assertEquals(first.to(), transport.pullRequests().getLast());
        assertEquals("b", context.getDatabase().getEntries().getFirst().getSharedBibEntryData().getSharedIdAsString());
    }

    @Test
    void hashesRoundTripThroughMetaData() {
        BibDatabaseContext context = new BibDatabaseContext();
        BibEntry entry = new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, "Title");
        entry.getSharedBibEntryData().setSharedId("e1");
        entry.getSharedBibEntryData().setVersion(1);
        context.getDatabase().insertEntry(entry);
        String hash = hasher.hash(entry);
        metadata.writeEntries(context.getMetaData(), Map.of("e1", new SyncMetadata.StoredEntry(hash, 1)));

        SynchronizationManager manager = manager(context, new InMemorySyncTransport());

        assertEquals(hash, manager.storedHash("e1").orElseThrow());
        assertEquals(false, manager.isDirty("e1"));
    }

    @Test
    void tombstonesRoundTripUntilAcknowledged() throws Exception {
        BibDatabaseContext context = new BibDatabaseContext();
        metadata.writeTombstones(context.getMetaData(), Map.of("e1", 1));
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("e1", 1, "A"));
        SynchronizationManager loaded = manager(context, transport);
        SynchronizationManager reloaded = manager(context, transport);

        assertEquals(List.of("e1"), loaded.pendingTombstoneIds());
        assertEquals(List.of("e1"), reloaded.pendingTombstoneIds());
        reloaded.synchronize();
        SynchronizationManager afterPush = manager(context, transport);

        assertEquals(List.of(), reloaded.pendingTombstoneIds());
        assertEquals(List.of(), afterPush.pendingTombstoneIds());
    }

    private SynchronizationManager manager(BibDatabaseContext context, InMemorySyncTransport transport) {
        return new SynchronizationManager(context, transport, hasher, planner, new CurrentThreadTaskExecutor());
    }

    private SyncDocument document(String id, int version, String title) {
        BibEntry snapshot = new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, title);
        snapshot.getSharedBibEntryData().setVersion(version);
        return new SyncDocument(id, version, hasher.hash(snapshot), snapshot.getType().getName(), "", Map.of(StandardField.TITLE.getName(), title), false);
    }
}
