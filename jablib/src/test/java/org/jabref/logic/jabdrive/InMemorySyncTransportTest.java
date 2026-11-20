package org.jabref.logic.jabdrive;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemorySyncTransportTest {

    @Test
    void pullFromStartReturnsEntriesAddedSinceZero() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("a", 1, "h1", "A"));
        transport.insert(document("b", 1, "h2", "B"));

        PullBatch batch = transport.pull(SyncCheckpoint.EMPTY);

        assertEquals(List.of("a", "b"), batch.documents().stream().map(SyncDocument::id).toList());
        assertEquals(true, batch.to().serverTime() > 0);
    }

    // [utest->req~jabdrive.incremental-sync~1]
    @Test
    void pullAfterCheckpointReturnsOnlyNewerEntries() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("a", 1, "h1", "A"));
        PullBatch first = transport.pull(SyncCheckpoint.EMPTY);
        transport.insert(document("b", 1, "h2", "B"));

        PullBatch second = transport.pull(first.to());

        assertEquals(List.of("b"), second.documents().stream().map(SyncDocument::id).toList());
    }

    @Test
    void pullBatchesAndResumes() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport(2);
        transport.insert(document("a", 1, "h1", "A"));
        transport.insert(document("b", 1, "h2", "B"));
        transport.insert(document("c", 1, "h3", "C"));

        PullBatch first = transport.pull(SyncCheckpoint.EMPTY);
        PullBatch second = transport.pull(first.to());

        assertEquals(List.of("a", "b"), first.documents().stream().map(SyncDocument::id).toList());
        assertEquals(List.of("c"), second.documents().stream().map(SyncDocument::id).toList());
    }

    @Test
    void pushAcceptedIncrementsVersion() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("e1", 1, "before", "Before"));

        PushResult result = transport.push(new ChangeSet(List.of(document("e1", 1, "after", "After")), List.of(), List.of()));

        assertEquals(2, result.accepted().getFirst().version());
        assertEquals(2, transport.stored("e1").orElseThrow().version());
        assertEquals("After", transport.stored("e1").orElseThrow().fields().get("title"));
    }

    @Test
    void pushRejectedWhenVersionMismatch() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("e1", 2, "server", "Server"));

        PushResult result = transport.push(new ChangeSet(List.of(document("e1", 1, "local", "Local")), List.of(), List.of()));

        assertEquals(List.of(new RejectedUpdate("e1", 2)), result.rejected());
        assertEquals(2, transport.stored("e1").orElseThrow().version());
        assertEquals("Server", transport.stored("e1").orElseThrow().fields().get("title"));
    }

    // [utest->req~jabdrive.identical-resend~1]
    @Test
    void pushOfIdenticalResendReturnsAssignedVersion() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("e1", 1, "hash", "A"));
        SyncDocument pushed = document("e1", 1, "hash", "A");
        transport.push(new ChangeSet(List.of(pushed), List.of(), List.of()));

        PushResult resend = transport.push(new ChangeSet(List.of(pushed), List.of(), List.of()));

        assertEquals(2, resend.accepted().getFirst().version());
        assertEquals(2, transport.stored("e1").orElseThrow().version());
    }

    @Test
    void pushOfNewEntryAssignsVersion() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        SyncDocument created = document("client-1", 0, "hash", "New");

        PushResult result = transport.push(new ChangeSet(List.of(), List.of(created), List.of()));

        assertEquals("client-1", result.accepted().getFirst().id());
        assertEquals(1, result.accepted().getFirst().version());
        assertEquals(1, transport.stored("client-1").orElseThrow().version());
    }

    @Test
    void pushTombstoneHidesEntryFromLaterPull() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("e1", 1, "hash", "A"));
        PullBatch baseline = transport.pull(SyncCheckpoint.EMPTY);
        transport.push(new ChangeSet(List.of(), List.of(), List.of(tombstone("e1", 1))));

        PullBatch afterDelete = transport.pull(baseline.to());
        PullBatch later = transport.pull(afterDelete.to());

        assertEquals(List.of("e1"), afterDelete.documents().stream().map(SyncDocument::id).toList());
        assertEquals(true, afterDelete.documents().getFirst().tombstone());
        assertEquals(List.of(), later.documents());
    }

    @Test
    void pullIncludesTombstone() throws Exception {
        InMemorySyncTransport transport = new InMemorySyncTransport();
        transport.insert(document("e1", 1, "hash", "A"));
        PullBatch baseline = transport.pull(SyncCheckpoint.EMPTY);
        transport.delete("e1");

        PullBatch pulled = transport.pull(baseline.to());

        assertEquals(true, pulled.documents().getFirst().tombstone());
        assertEquals("e1", pulled.documents().getFirst().id());
    }

    private SyncDocument document(String id, int version, String hash, String title) {
        return new SyncDocument(id, version, hash, "article", "", Map.of("title", title), false);
    }

    private SyncDocument tombstone(String id, int version) {
        return new SyncDocument(id, version, "", "", "", Map.of(), true);
    }
}
