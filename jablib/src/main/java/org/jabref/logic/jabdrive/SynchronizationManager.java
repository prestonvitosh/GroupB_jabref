package org.jabref.logic.jabdrive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.jabref.logic.JabRefException;
import org.jabref.logic.util.BackgroundTask;
import org.jabref.logic.util.CoarseChangeFilter;
import org.jabref.logic.util.TaskExecutor;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.event.BibDatabaseContextChangedEvent;
import org.jabref.model.database.event.EntriesRemovedEvent;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.event.EntriesEventSource;
import org.jabref.model.entry.event.FieldChangedEvent;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.FieldFactory;
import org.jabref.model.entry.field.InternalField;
import org.jabref.model.entry.types.EntryTypeFactory;
import org.jabref.model.metadata.MetaData;

import com.google.common.eventbus.Subscribe;
import io.github.thibaultmeyer.cuid.CUID;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Runs the JabDrive pull-merge-push cycle for one open library.
///
/// One cycle runs at a time. Entries included in the current push stay dirty when they are edited
/// again before the push returns. Network calls go through [BackgroundTask]; applying the result
/// happens on the thread that calls [SynchronizationManager#synchronize].
@NullMarked
public class SynchronizationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SynchronizationManager.class);

    private final BibDatabaseContext context;
    private final SyncTransport transport;
    private final EntrySyncHasher hasher;
    private final MergePlanner planner;
    private final TaskExecutor taskExecutor;
    private final SyncMetadata metadata = new SyncMetadata();
    private final CoarseChangeFilter changeFilter;
    private final Object cycleLock = new Object();

    private final Set<String> knownIds = new LinkedHashSet<>();
    private final Set<String> dirty = new HashSet<>();
    private final Map<String, String> storedHashes = new HashMap<>();
    private final Map<String, Integer> tombstones = new LinkedHashMap<>();
    private final Set<String> pushing = new HashSet<>();
    private final Set<String> editedDuringPush = new HashSet<>();
    private final List<SyncConflict> conflicts = new ArrayList<>();

    private SyncCheckpoint checkpoint = SyncCheckpoint.EMPTY;

    public SynchronizationManager(BibDatabaseContext bibDatabaseContext,
                                  SyncTransport transport,
                                  EntrySyncHasher hasher,
                                  MergePlanner planner,
                                  TaskExecutor taskExecutor) {
        this.context = bibDatabaseContext;
        this.transport = transport;
        this.hasher = hasher;
        this.planner = planner;
        this.taskExecutor = taskExecutor;
        load();
        this.changeFilter = new CoarseChangeFilter(bibDatabaseContext);
        this.changeFilter.registerListener(this);
    }

    // [impl->req~jabdrive.incremental-sync~1]
    public SyncCycleResult synchronize() throws JabRefException {
        synchronized (cycleLock) {
            return runCycle();
        }
    }

    public void recordLocalEdit(BibEntry entry) {
        synchronized (cycleLock) {
            String id = sharedId(entry);
            if (id.isEmpty()) {
                return;
            }
            dirty.add(id);
            if (pushing.contains(id)) {
                editedDuringPush.add(id);
            }
        }
    }

    public boolean isDirty(String id) {
        synchronized (cycleLock) {
            return dirty.contains(id);
        }
    }

    public List<String> pendingTombstoneIds() {
        synchronized (cycleLock) {
            return List.copyOf(tombstones.keySet());
        }
    }

    public SyncCheckpoint checkpoint() {
        synchronized (cycleLock) {
            return checkpoint;
        }
    }

    public Optional<String> storedHash(String id) {
        synchronized (cycleLock) {
            return Optional.ofNullable(storedHashes.get(id));
        }
    }

    @Subscribe
    public void onLibraryChange(BibDatabaseContextChangedEvent event) {
        if (event instanceof FieldChangedEvent fieldChanged) {
            if (fieldChanged.getEntriesEventSource() == EntriesEventSource.LOCAL) {
                recordLocalEdit(fieldChanged.getBibEntry());
            }
            return;
        }
        if (event instanceof EntriesRemovedEvent removed && removed.getEntriesEventSource() == EntriesEventSource.LOCAL) {
            removed.getBibEntries().forEach(this::noteLocalDeletion);
        }
    }

    private SyncCycleResult runCycle() throws JabRefException {
        conflicts.clear();
        PullBatch batch = runTransport(() -> transport.pull(checkpoint));
        for (SyncDocument remote : batch.documents()) {
            applyRemote(remote);
        }
        checkpoint = batch.to();
        persist();

        ChangeSet changeSet = collectChanges();
        if (changeSet.isEmpty()) {
            return new SyncCycleResult(conflicts);
        }

        Map<String, String> pushedHashes = new HashMap<>();
        changeSet.dirtyUpdates().forEach(document -> pushedHashes.put(document.id(), document.contentHash()));
        changeSet.newEntries().forEach(document -> pushedHashes.put(document.id(), document.contentHash()));
        pushing.addAll(idsOf(changeSet));
        try {
            PushResult result = runTransport(() -> transport.push(changeSet));
            applyPushResult(result, pushedHashes);
            persist();
        } catch (SyncResponseLostException exception) {
            LOGGER.debug("Push response lost", exception);
            persist();
        } finally {
            pushing.clear();
            editedDuringPush.clear();
        }
        return new SyncCycleResult(conflicts);
    }

    private void applyRemote(SyncDocument remote) throws SyncVersionRegressionException {
        Optional<BibEntry> local = find(remote.id());
        if (local.isPresent()) {
            BibEntry entry = local.get();
            boolean sameContent = hasher.hash(entry).equals(remote.contentHash());
            if (!remote.tombstone()
                    && remote.version() >= entry.getSharedBibEntryData().getVersion()
                    && isDirty(remote.id())
                    && sameContent) {
                return;
            }
        }
        MergeDecision decision = planner.plan(stateOf(remote.id(), local), remote);
        switch (decision.action()) {
            case REPLACE -> applyReplace(remote);
            case DELETE -> applyDelete(remote);
            case CONFLICT -> {
                // [impl->req~jabdrive.dirty-entry-kept~1]
                conflicts.add(new SyncConflict(remote.id(), remote));
            }
            case NO_OP -> {
                if (remote.tombstone()) {
                    tombstones.remove(remote.id());
                }
            }
        }
    }

    private void applyReplace(SyncDocument remote) {
        Optional<BibEntry> existing = find(remote.id());
        if (existing.isPresent()) {
            updateEntry(existing.get(), remote);
        } else {
            context.getDatabase().insertEntry(createEntry(remote), EntriesEventSource.SHARED);
        }
        dirty.remove(remote.id());
        knownIds.add(remote.id());
        storedHashes.put(remote.id(), remote.contentHash());
    }

    private void applyDelete(SyncDocument remote) {
        find(remote.id()).ifPresent(entry -> context.getDatabase().removeEntry(entry, EntriesEventSource.SHARED));
        tombstones.remove(remote.id());
        dirty.remove(remote.id());
        knownIds.remove(remote.id());
        storedHashes.remove(remote.id());
    }

    private ChangeSet collectChanges() {
        Set<String> conflictIds = conflicts.stream().map(SyncConflict::sharedId).collect(Collectors.toSet());
        List<SyncDocument> dirtyUpdates = new ArrayList<>();
        List<SyncDocument> newEntries = new ArrayList<>();
        List<SyncDocument> removed = new ArrayList<>();
        for (Map.Entry<String, Integer> tombstone : tombstones.entrySet()) {
            if (!conflictIds.contains(tombstone.getKey())) {
                removed.add(new SyncDocument(tombstone.getKey(), tombstone.getValue(), "", "", "", Map.of(), true));
            }
        }
        for (BibEntry entry : List.copyOf(context.getDatabase().getEntries())) {
            String id = sharedId(entry);
            if (conflictIds.contains(id)) {
                continue;
            }
            if (id.isEmpty() || !knownIds.contains(id)) {
                if (id.isEmpty()) {
                    entry.getSharedBibEntryData().setSharedId(CUID.randomCUID2().toString());
                }
                newEntries.add(toDocument(entry));
                continue;
            }
            if (dirty.contains(id)) {
                dirtyUpdates.add(toDocument(entry));
            }
        }
        return new ChangeSet(dirtyUpdates, newEntries, removed);
    }

    private void applyPushResult(PushResult result, Map<String, String> pushedHashes) {
        for (AcceptedUpdate accepted : result.accepted()) {
            Optional<BibEntry> entry = find(accepted.id());
            entry.ifPresentOrElse(found -> acceptLiveEntry(accepted, found, pushedHashes), () -> acceptTombstone(accepted.id()));
            editedDuringPush.remove(accepted.id());
        }
        for (RejectedUpdate rejected : result.rejected()) {
            if (!tombstones.containsKey(rejected.id())) {
                dirty.add(rejected.id());
            }
        }
    }

    private void acceptLiveEntry(AcceptedUpdate accepted, BibEntry entry, Map<String, String> pushedHashes) {
        entry.getSharedBibEntryData().setVersion(accepted.version());
        knownIds.add(accepted.id());
        String sentHash = pushedHashes.get(accepted.id());
        if (sentHash != null) {
            storedHashes.put(accepted.id(), sentHash);
        }
        if (!editedDuringPush.contains(accepted.id())) {
            dirty.remove(accepted.id());
        }
    }

    private void acceptTombstone(String id) {
        tombstones.remove(id);
        knownIds.remove(id);
        storedHashes.remove(id);
        dirty.remove(id);
    }

    private void load() {
        MetaData metaData = context.getMetaData();
        checkpoint = metadata.readCheckpoint(metaData);
        Map<String, SyncMetadata.StoredEntry> storedEntries = metadata.readEntries(metaData);
        storedEntries.forEach((id, stored) -> {
            knownIds.add(id);
            storedHashes.put(id, stored.contentHash());
        });
        tombstones.putAll(metadata.readTombstones(metaData));
        tombstones.keySet().forEach(knownIds::remove);

        Set<String> present = new HashSet<>();
        for (BibEntry entry : context.getDatabase().getEntries()) {
            String id = sharedId(entry);
            if (id.isEmpty()) {
                continue;
            }
            present.add(id);
            String storedHash = storedHashes.get(id);
            if (storedHash != null && !storedHash.equals(hasher.hash(entry))) {
                dirty.add(id);
            }
        }
        List<String> missing = knownIds.stream()
                                        .filter(id -> !present.contains(id) && !tombstones.containsKey(id))
                                        .toList();
        for (String id : missing) {
            SyncMetadata.StoredEntry stored = storedEntries.get(id);
            int version = stored == null ? 1 : stored.version();
            tombstones.put(id, version);
            knownIds.remove(id);
            storedHashes.remove(id);
        }
    }

    private void persist() {
        MetaData metaData = context.getMetaData();
        metadata.writeCheckpoint(metaData, checkpoint);
        Map<String, SyncMetadata.StoredEntry> entries = new TreeMap<>();
        for (BibEntry entry : context.getDatabase().getEntries()) {
            String id = sharedId(entry);
            if (id.isEmpty() || !knownIds.contains(id)) {
                continue;
            }
            String hash = storedHashes.getOrDefault(id, hasher.hash(entry));
            entries.put(id, new SyncMetadata.StoredEntry(hash, entry.getSharedBibEntryData().getVersion()));
        }
        metadata.writeEntries(metaData, entries);
        metadata.writeTombstones(metaData, tombstones);
    }

    private LocalEntryState stateOf(String id, Optional<BibEntry> entry) {
        if (entry.isPresent()) {
            return new LocalEntryState(entry.get().getSharedBibEntryData().getVersion(), dirty.contains(id), false);
        }
        if (tombstones.containsKey(id)) {
            return new LocalEntryState(tombstones.get(id), false, true);
        }
        return new LocalEntryState(0, false, false);
    }

    private void noteLocalDeletion(BibEntry entry) {
        synchronized (cycleLock) {
            String id = sharedId(entry);
            if (id.isEmpty() || !knownIds.contains(id)) {
                return;
            }
            tombstones.put(id, entry.getSharedBibEntryData().getVersion());
            dirty.remove(id);
            knownIds.remove(id);
            storedHashes.remove(id);
        }
    }

    private SyncDocument toDocument(BibEntry entry) {
        Map<String, String> fields = new TreeMap<>();
        for (Field field : entry.getFields()) {
            if (InternalField.KEY_FIELD.equals(field)) {
                continue;
            }
            entry.getField(field).ifPresent(value -> fields.put(field.getName(), value));
        }
        return new SyncDocument(
                entry.getSharedBibEntryData().getSharedIdAsString(),
                entry.getSharedBibEntryData().getVersion(),
                hasher.hash(entry),
                entry.getType().getName(),
                entry.getCitationKey().orElse(""),
                fields,
                false);
    }

    private BibEntry createEntry(SyncDocument remote) {
        BibEntry entry = new BibEntry(EntryTypeFactory.parse(remote.entryType()));
        entry.getSharedBibEntryData().setSharedId(remote.id());
        entry.getSharedBibEntryData().setVersion(remote.version());
        if (!remote.citationKey().isEmpty()) {
            entry.withCitationKey(remote.citationKey());
        }
        remote.fields().forEach((name, value) -> entry.withField(FieldFactory.parseField(name), value));
        return entry;
    }

    private void updateEntry(BibEntry entry, SyncDocument remote) {
        entry.getSharedBibEntryData().setVersion(remote.version());
        entry.setType(EntryTypeFactory.parse(remote.entryType()), EntriesEventSource.SHARED);
        if (remote.citationKey().isEmpty()) {
            entry.clearField(InternalField.KEY_FIELD, EntriesEventSource.SHARED);
        } else {
            entry.setField(InternalField.KEY_FIELD, remote.citationKey(), EntriesEventSource.SHARED);
        }
        List<Field> existingFields = List.copyOf(entry.getFields());
        for (Field field : existingFields) {
            if (InternalField.KEY_FIELD.equals(field)) {
                continue;
            }
            if (!remote.fields().containsKey(field.getName())) {
                entry.clearField(field, EntriesEventSource.SHARED);
            }
        }
        remote.fields().forEach((name, value) -> entry.setField(FieldFactory.parseField(name), value, EntriesEventSource.SHARED));
    }

    private Optional<BibEntry> find(String id) {
        return context.getDatabase().getEntries().stream()
                      .filter(entry -> id.equals(entry.getSharedBibEntryData().getSharedIdAsString()))
                      .findFirst();
    }

    private Set<String> idsOf(ChangeSet changeSet) {
        Set<String> ids = new HashSet<>();
        changeSet.dirtyUpdates().forEach(document -> ids.add(document.id()));
        changeSet.newEntries().forEach(document -> ids.add(document.id()));
        changeSet.tombstones().forEach(document -> ids.add(document.id()));
        return ids;
    }

    private String sharedId(BibEntry entry) {
        return entry.getSharedBibEntryData().getSharedIdAsString();
    }

    private <V> V runTransport(Callable<V> callable) throws JabRefException {
        BackgroundTask<V> task = BackgroundTask.wrap(callable)
                                               .onFailure(exception -> LOGGER.debug("Synchronization transport call failed", exception));
        try {
            Future<V> future = taskExecutor.execute(task);
            return future.get();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SyncResponseLostException lost) {
                throw lost;
            }
            if (cause instanceof JabRefException jabRefException) {
                throw jabRefException;
            }
            if (cause == null) {
                throw new JabRefException("Synchronization transport call failed", exception);
            }
            throw new JabRefException("Synchronization transport call failed", cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JabRefException("Synchronization was interrupted", exception);
        }
    }
}
