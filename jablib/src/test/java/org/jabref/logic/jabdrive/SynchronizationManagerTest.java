package org.jabref.logic.jabdrive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jabref.logic.JabRefException;
import org.jabref.logic.util.CurrentThreadTaskExecutor;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.event.EntriesAddedEvent;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.event.EntriesEventSource;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import com.google.common.eventbus.Subscribe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SynchronizationManagerTest {

    private final EntrySyncHasher hasher = new EntrySyncHasher();
    private final MergePlanner planner = new MergePlanner();
    private BibDatabaseContext context;
    private InMemorySyncTransport transport;
    private SynchronizationManager manager;

    @BeforeEach
    void setUp() {
        context = new BibDatabaseContext();
        transport = new InMemorySyncTransport();
    }

    @Test
    void cycleAppliesCleanRemoteUpdate() throws Exception {
        BibEntry entry = entry("e1", 1, "Old");
        transport.insert(document("e1", 2, "New"));
        start();

        manager.synchronize();

        assertEquals("New", entry.getField(StandardField.TITLE).orElseThrow());
        assertEquals(2, entry.getSharedBibEntryData().getVersion());
        assertEquals(false, manager.isDirty("e1"));
        assertNotEquals(SyncCheckpoint.EMPTY, manager.checkpoint());
    }

    // [utest->req~jabdrive.dirty-entry-kept~1]
    @Test
    void cycleReportsConflictForDirtyLocalEntry() throws Exception {
        BibEntry entry = entry("e1", 1, "Local");
        start();
        entry.setField(StandardField.TITLE, "Local edit");
        transport.insert(document("e1", 2, "Remote"));

        SyncCycleResult result = manager.synchronize();

        assertEquals("e1", result.conflicts().getFirst().sharedId());
        assertEquals("Local edit", entry.getField(StandardField.TITLE).orElseThrow());
        assertEquals(0, transport.pushCount());
    }

    @Test
    void cyclePushesDirtyEntryAndClearsDirty() throws Exception {
        BibEntry entry = entry("e1", 1, "A");
        remember(entry);
        transport.insert(document("e1", 1, "A"));
        start();
        entry.setField(StandardField.TITLE, "B");

        manager.synchronize();

        assertEquals(2, entry.getSharedBibEntryData().getVersion());
        assertEquals(false, manager.isDirty("e1"));
    }

    @Test
    void cyclePushesEntryWithoutId() throws Exception {
        start();
        BibEntry entry = new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, "New");
        context.getDatabase().insertEntry(entry);

        manager.synchronize();

        String id = entry.getSharedBibEntryData().getSharedIdAsString();
        assertNotEquals("", id);
        assertEquals(1, entry.getSharedBibEntryData().getVersion());
        assertEquals(1, transport.stored(id).orElseThrow().version());
    }

    @Test
    void cyclePushesDeletionAsTombstone() throws Exception {
        BibEntry entry = entry("e1", 1, "A");
        remember(entry);
        transport.insert(document("e1", 1, "A"));
        start();
        context.getDatabase().removeEntry(entry);

        manager.synchronize();

        PullBatch afterDelete = transport.pull(SyncCheckpoint.EMPTY);
        PullBatch later = transport.pull(afterDelete.to());
        assertEquals(List.of(), manager.pendingTombstoneIds());
        assertEquals(List.of(), List.copyOf(context.getDatabase().getEntries()));
        assertEquals(true, afterDelete.documents().getFirst().tombstone());
        assertEquals(List.of(), later.documents());
    }

    @Test
    void rejectedPushStaysDirtyAndRetries() throws Exception {
        BibEntry entry = entry("e1", 1, "A");
        remember(entry);
        entry.setField(StandardField.TITLE, "local-edit");
        RejectThenRevealTransport rejecting = new RejectThenRevealTransport(document("e1", 2, "Remote"));
        manager = new SynchronizationManager(context, rejecting, hasher, planner, new CurrentThreadTaskExecutor());

        SyncCycleResult rejected = manager.synchronize();
        SyncCycleResult conflict = manager.synchronize();

        assertEquals(List.of(), rejected.conflicts());
        assertEquals(true, manager.isDirty("e1"));
        assertEquals(1, entry.getSharedBibEntryData().getVersion());
        assertEquals("e1", conflict.conflicts().getFirst().sharedId());
        assertEquals("local-edit", entry.getField(StandardField.TITLE).orElseThrow());
    }

    @Test
    void lostResponseResendIsAccepted() throws Exception {
        BibEntry entry = entry("e1", 1, "A");
        remember(entry);
        transport.insert(document("e1", 1, "A"));
        start();
        entry.setField(StandardField.TITLE, "B");
        transport.loseNextPushResponse();

        manager.synchronize();
        assertEquals(1, entry.getSharedBibEntryData().getVersion());
        assertEquals(true, manager.isDirty("e1"));

        manager.synchronize();
        assertEquals(2, entry.getSharedBibEntryData().getVersion());
        assertEquals(false, manager.isDirty("e1"));
    }

    @Test
    void cycleDoesNotRepeatAfterCheckpointStored() throws Exception {
        BibEntry entry = entry("e1", 1, "Old");
        remember(entry);
        transport.insert(document("e1", 2, "New"));
        start();

        manager.synchronize();
        manager.synchronize();

        assertEquals("New", entry.getField(StandardField.TITLE).orElseThrow());
        assertEquals(0, transport.pushCount());
        assertEquals(List.of(1, 0), transport.pullSizes());
    }

    @Test
    void checkpointNotAdvancedWhenCycleStopsDuringMerge() {
        entry("e1", 5, "Local");
        transport.insert(document("e1", 3, "Remote"));
        start();

        assertThrows(SyncVersionRegressionException.class, () -> manager.synchronize());
        assertThrows(SyncVersionRegressionException.class, () -> manager.synchronize());
        assertEquals(List.of(SyncCheckpoint.EMPTY, SyncCheckpoint.EMPTY), transport.pullRequests());
    }

    @Test
    void secondCycleWaitsForTheFirst() throws Exception {
        BibEntry first = entry("a", 1, "A");
        BibEntry second = entry("b", 1, "B");
        remember(first, second);
        transport.insert(document("a", 1, "A"));
        transport.insert(document("b", 1, "B"));
        start();
        first.setField(StandardField.TITLE, "A2");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.pauseNextPull(entered, release);
        transport.runDuringNextPush(() -> second.setField(StandardField.TITLE, "B2"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SyncCycleResult> firstCycle = executor.submit((Callable<SyncCycleResult>) () -> manager.synchronize());
            assertEquals(true, entered.await(5, TimeUnit.SECONDS));
            Future<SyncCycleResult> secondCycle = executor.submit((Callable<SyncCycleResult>) () -> manager.synchronize());
            assertEquals(0, transport.pushCount());
            release.countDown();
            firstCycle.get(5, TimeUnit.SECONDS);
            secondCycle.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of("start", "end", "start", "end"), transport.pushTrace());
    }

    @Test
    void editDuringPushStaysDirty() throws Exception {
        BibEntry entry = entry("e1", 1, "A");
        remember(entry);
        transport.insert(document("e1", 1, "A"));
        start();
        entry.setField(StandardField.TITLE, "middle");
        transport.runDuringNextPush(() -> entry.setField(StandardField.TITLE, "during"));

        manager.synchronize();

        assertEquals("during", entry.getField(StandardField.TITLE).orElseThrow());
        assertEquals(true, manager.isDirty("e1"));
    }

    @Test
    void externalEditDetectedOnLoad() {
        BibEntry entry = entry("e1", 1, "original");
        String originalHash = hasher.hash(entry);
        entry.setField(StandardField.TITLE, "outside");
        new SyncMetadata().writeEntries(context.getMetaData(), Map.of("e1", new SyncMetadata.StoredEntry(originalHash, 1)));
        start();

        assertEquals(true, manager.isDirty("e1"));
    }

    @Test
    void externalDeletionBecomesTombstoneOnLoad() throws Exception {
        BibEntry removed = new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, "A");
        removed.getSharedBibEntryData().setSharedId("e1");
        removed.getSharedBibEntryData().setVersion(1);
        new SyncMetadata().writeEntries(context.getMetaData(), Map.of("e1", new SyncMetadata.StoredEntry(hasher.hash(removed), 1)));
        transport.insert(document("e1", 1, "A"));
        start();

        assertEquals(List.of("e1"), manager.pendingTombstoneIds());
        manager.synchronize();
        assertEquals(List.of(), manager.pendingTombstoneIds());
        assertEquals(true, transport.stored("e1").orElseThrow().tombstone());
    }

    @Test
    void sharedSourceEventsAreNotUndoCandidates() throws Exception {
        start();
        List<EntriesEventSource> sources = new ArrayList<>();
        context.getDatabase().registerListener(new Object() {
            @Subscribe
            public void added(EntriesAddedEvent event) {
                sources.add(event.getEntriesEventSource());
            }
        });
        transport.insert(document("e1", 1, "Remote"));

        manager.synchronize();

        assertEquals(List.of(EntriesEventSource.SHARED), sources);
    }

    @Test
    void remoteTombstoneDeletesCleanEntryWithoutLocalTombstone() throws Exception {
        entry("e1", 1, "A");
        start();
        transport.insert(new SyncDocument("e1", 2, "", "", "", Map.of(), true));

        manager.synchronize();

        assertEquals(List.of(), List.copyOf(context.getDatabase().getEntries()));
        assertEquals(List.of(), manager.pendingTombstoneIds());
    }

    private void start() {
        manager = new SynchronizationManager(context, transport, hasher, planner, new CurrentThreadTaskExecutor());
    }

    private BibEntry entry(String id, int version, String title) {
        BibEntry created = new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, title);
        created.getSharedBibEntryData().setSharedId(id);
        created.getSharedBibEntryData().setVersion(version);
        context.getDatabase().insertEntry(created);
        return created;
    }

    private void remember(BibEntry... entries) {
        Map<String, SyncMetadata.StoredEntry> stored = new LinkedHashMap<>();
        for (BibEntry storedEntry : entries) {
            stored.put(storedEntry.getSharedBibEntryData().getSharedIdAsString(),
                    new SyncMetadata.StoredEntry(hasher.hash(storedEntry), storedEntry.getSharedBibEntryData().getVersion()));
        }
        new SyncMetadata().writeEntries(context.getMetaData(), stored);
    }

    private SyncDocument document(String id, int version, String title) {
        BibEntry snapshot = new BibEntry(StandardEntryType.Article).withField(StandardField.TITLE, title);
        snapshot.getSharedBibEntryData().setVersion(version);
        return new SyncDocument(id, version, hasher.hash(snapshot), snapshot.getType().getName(), "", Map.of(StandardField.TITLE.getName(), title), false);
    }

    private static final class RejectThenRevealTransport implements SyncTransport {

        private final SyncDocument remoteDocument;
        private int pulls;

        private RejectThenRevealTransport(SyncDocument remoteDocument) {
            this.remoteDocument = remoteDocument;
        }

        @Override
        public PullBatch pull(SyncCheckpoint since) {
            pulls++;
            if (pulls == 1) {
                return new PullBatch(List.of(), since);
            }
            return new PullBatch(List.of(remoteDocument), new SyncCheckpoint(since.serverTime() + 1, remoteDocument.id()));
        }

        @Override
        public PushResult push(ChangeSet changeSet) throws JabRefException {
            return new PushResult(List.of(), List.of(new RejectedUpdate(changeSet.dirtyUpdates().getFirst().id(), remoteDocument.version())));
        }
    }
}
