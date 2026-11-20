package org.jabref.logic.jabdrive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import org.jabref.logic.JabRefException;

import io.github.thibaultmeyer.cuid.CUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A remote that keeps entries, tombstones, and a monotone clock in memory.
///
/// A push is accepted only when the sent version is the one currently stored. Sending the same id,
/// version, and hash again returns the version already assigned and does not allocate another one.
@NullMarked
public final class InMemorySyncTransport implements SyncTransport {

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final int batchSize;
    private final Map<String, Stored> documents = new HashMap<>();
    private final Map<String, Integer> acceptedResends = new HashMap<>();
    private final List<SyncCheckpoint> pullRequests = new ArrayList<>();
    private final List<Integer> pullSizes = new ArrayList<>();
    private final List<String> pushTrace = new ArrayList<>();

    private long clock;
    private int pushCount;
    private boolean loseNextResponse;
    private Optional<Runnable> duringPush = Optional.empty();
    private Optional<PullPause> pullPause = Optional.empty();

    public InMemorySyncTransport() {
        this(DEFAULT_BATCH_SIZE);
    }

    public InMemorySyncTransport(int batchSize) {
        this.batchSize = batchSize;
    }

    public void insert(SyncDocument document) {
        documents.put(document.id(), new Stored(document, nextTime()));
    }

    /// Replaces a stored entry with a tombstone at the next version.
    public void delete(String id) {
        Stored stored = documents.get(id);
        if (stored == null) {
            return;
        }
        SyncDocument tombstone = new SyncDocument(id, stored.document().version() + 1, "", "", "", Map.of(), true);
        documents.put(id, new Stored(tombstone, nextTime()));
    }

    public Optional<SyncDocument> stored(String id) {
        Stored stored = documents.get(id);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.document());
    }

    public List<SyncCheckpoint> pullRequests() {
        return List.copyOf(pullRequests);
    }

    public List<Integer> pullSizes() {
        return List.copyOf(pullSizes);
    }

    public int pushCount() {
        return pushCount;
    }

    public List<String> pushTrace() {
        return List.copyOf(pushTrace);
    }

    public void pauseNextPull(CountDownLatch entered, CountDownLatch release) {
        this.pullPause = Optional.of(new PullPause(entered, release));
    }

    public void loseNextPushResponse() {
        this.loseNextResponse = true;
    }

    public void runDuringNextPush(Runnable action) {
        this.duringPush = Optional.of(action);
    }

    @Override
    public PullBatch pull(SyncCheckpoint since) throws JabRefException {
        awaitPullPause();
        pullRequests.add(since);
        List<Stored> pending = documents.values().stream()
                                        .filter(stored -> isAfter(stored, since))
                                        .sorted(Comparator.comparingLong(Stored::serverTime).thenComparing(stored -> stored.document().id()))
                                        .limit(batchSize)
                                        .toList();
        SyncCheckpoint to = pending.isEmpty()
                            ? since
                            : new SyncCheckpoint(pending.getLast().serverTime(), pending.getLast().document().id());
        List<SyncDocument> documentsInPage = pending.stream().map(Stored::document).toList();
        pullSizes.add(documentsInPage.size());
        return new PullBatch(documentsInPage, to);
    }

    @Override
    public PushResult push(ChangeSet changeSet) throws JabRefException {
        pushCount++;
        pushTrace.add("start");
        try {
            PushResult result = apply(changeSet);
            Optional<Runnable> action = duringPush;
            duringPush = Optional.empty();
            action.ifPresent(Runnable::run);
            if (loseNextResponse) {
                loseNextResponse = false;
                throw new SyncResponseLostException();
            }
            return result;
        } finally {
            pushTrace.add("end");
        }
    }

    private PushResult apply(ChangeSet changeSet) {
        List<AcceptedUpdate> accepted = new ArrayList<>();
        List<RejectedUpdate> rejected = new ArrayList<>();
        changeSet.dirtyUpdates().forEach(document -> pushUpdate(document, accepted, rejected));
        changeSet.newEntries().forEach(document -> pushNew(document, accepted, rejected));
        changeSet.tombstones().forEach(document -> pushTombstone(document, accepted, rejected));
        return new PushResult(accepted, rejected);
    }

    private void pushUpdate(SyncDocument incoming, List<AcceptedUpdate> accepted, List<RejectedUpdate> rejected) {
        Stored stored = documents.get(incoming.id());
        if (stored != null && stored.document().version() == incoming.version() && !stored.document().tombstone()) {
            acceptReplacement(incoming, incoming.version() + 1, false, accepted);
            return;
        }
        // [impl->req~jabdrive.identical-resend~1]
        if (resend(incoming, stored, accepted)) {
            return;
        }
        int remoteVersion = stored == null ? 0 : stored.document().version();
        rejected.add(new RejectedUpdate(incoming.id(), remoteVersion));
    }

    private void pushNew(SyncDocument incoming, List<AcceptedUpdate> accepted, List<RejectedUpdate> rejected) {
        String id = incoming.id().isEmpty() ? CUID.randomCUID2().toString() : incoming.id();
        Stored stored = documents.get(id);
        if (stored != null && stored.document().contentHash().equals(incoming.contentHash())) {
            accepted.add(new AcceptedUpdate(id, stored.document().version()));
            return;
        }
        if (stored != null) {
            rejected.add(new RejectedUpdate(id, stored.document().version()));
            return;
        }
        SyncDocument created = new SyncDocument(id, 1, incoming.contentHash(), incoming.entryType(), incoming.citationKey(), incoming.fields(), false);
        documents.put(id, new Stored(created, nextTime()));
        accepted.add(new AcceptedUpdate(id, 1));
    }

    private void pushTombstone(SyncDocument incoming, List<AcceptedUpdate> accepted, List<RejectedUpdate> rejected) {
        Stored stored = documents.get(incoming.id());
        if (stored != null && stored.document().version() == incoming.version()) {
            acceptReplacement(incoming, incoming.version() + 1, true, accepted);
            return;
        }
        if (resend(incoming, stored, accepted)) {
            return;
        }
        int remoteVersion = stored == null ? 0 : stored.document().version();
        rejected.add(new RejectedUpdate(incoming.id(), remoteVersion));
    }

    private void acceptReplacement(SyncDocument incoming, int assignedVersion, boolean tombstone, List<AcceptedUpdate> accepted) {
        acceptedResends.put(resendKey(incoming), assignedVersion);
        SyncDocument storedDocument = new SyncDocument(
                incoming.id(),
                assignedVersion,
                tombstone ? "" : incoming.contentHash(),
                tombstone ? "" : incoming.entryType(),
                tombstone ? "" : incoming.citationKey(),
                tombstone ? Map.of() : incoming.fields(),
                tombstone);
        documents.put(incoming.id(), new Stored(storedDocument, nextTime()));
        accepted.add(new AcceptedUpdate(incoming.id(), assignedVersion));
    }

    private boolean resend(SyncDocument incoming, @Nullable Stored stored, List<AcceptedUpdate> accepted) {
        if (stored == null) {
            return false;
        }
        Integer assigned = acceptedResends.get(resendKey(incoming));
        if (assigned == null) {
            return false;
        }
        if (stored.document().version() == assigned && (incoming.tombstone() || stored.document().contentHash().equals(incoming.contentHash()))) {
            accepted.add(new AcceptedUpdate(incoming.id(), assigned));
            return true;
        }
        return false;
    }

    private String resendKey(SyncDocument incoming) {
        return incoming.id() + "\n" + incoming.version() + "\n" + incoming.contentHash();
    }

    private boolean isAfter(Stored stored, SyncCheckpoint since) {
        if (stored.serverTime() > since.serverTime()) {
            return true;
        }
        if (stored.serverTime() < since.serverTime()) {
            return false;
        }
        return stored.document().id().compareTo(since.highestId()) > 0;
    }

    private void awaitPullPause() throws JabRefException {
        Optional<PullPause> pause = pullPause;
        pullPause = Optional.empty();
        if (pause.isEmpty()) {
            return;
        }
        PullPause active = pause.orElseThrow();
        active.entered().countDown();
        try {
            active.release().await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JabRefException("Pull wait was interrupted", exception);
        }
    }

    private long nextTime() {
        clock++;
        return clock;
    }

    private record Stored(SyncDocument document, long serverTime) {
    }

    private record PullPause(CountDownLatch entered, CountDownLatch release) {
    }
}
