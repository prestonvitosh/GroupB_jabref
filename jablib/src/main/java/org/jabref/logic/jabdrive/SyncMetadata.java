package org.jabref.logic.jabdrive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jabref.model.metadata.MetaData;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Checkpoint, last accepted hashes, and unacknowledged tombstones kept in library metadata.
@NullMarked
public final class SyncMetadata {

    public static final String CHECKPOINT = "jabdrive-checkpoint";
    public static final String ENTRIES = "jabdrive-entries";
    public static final String TOMBSTONES = "jabdrive-tombstones";

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncMetadata.class);

    public SyncCheckpoint readCheckpoint(MetaData metaData) {
        List<String> values = metaData.getUnknownMetaData().get(CHECKPOINT);
        if (values == null || values.size() < 2) {
            return SyncCheckpoint.EMPTY;
        }
        try {
            return new SyncCheckpoint(Long.parseLong(values.getFirst()), values.get(1));
        } catch (NumberFormatException exception) {
            LOGGER.debug("Could not read the JabDrive checkpoint", exception);
            return SyncCheckpoint.EMPTY;
        }
    }

    public void writeCheckpoint(MetaData metaData, SyncCheckpoint checkpoint) {
        metaData.putUnknownMetaDataItem(CHECKPOINT, List.of(Long.toString(checkpoint.serverTime()), checkpoint.highestId()));
    }

    public Map<String, StoredEntry> readEntries(MetaData metaData) {
        return readLines(metaData, ENTRIES);
    }

    public void writeEntries(MetaData metaData, Map<String, StoredEntry> entries) {
        List<String> lines = entries.entrySet().stream()
                                    .map(entry -> entry.getKey() + "|" + entry.getValue().contentHash() + "|" + entry.getValue().version())
                                    .toList();
        metaData.putUnknownMetaDataItem(ENTRIES, lines);
    }

    public Map<String, Integer> readTombstones(MetaData metaData) {
        Map<String, Integer> tombstones = new LinkedHashMap<>();
        for (Map.Entry<String, StoredEntry> entry : readLines(metaData, TOMBSTONES).entrySet()) {
            tombstones.put(entry.getKey(), entry.getValue().version());
        }
        return tombstones;
    }

    public void writeTombstones(MetaData metaData, Map<String, Integer> tombstones) {
        Map<String, StoredEntry> lines = new LinkedHashMap<>();
        tombstones.forEach((id, version) -> lines.put(id, new StoredEntry("", version)));
        List<String> encoded = lines.entrySet().stream()
                                    .map(entry -> entry.getKey() + "|" + entry.getValue().version())
                                    .toList();
        metaData.putUnknownMetaDataItem(TOMBSTONES, encoded);
    }

    private Map<String, StoredEntry> readLines(MetaData metaData, String key) {
        List<String> lines = metaData.getUnknownMetaData().get(key);
        Map<String, StoredEntry> entries = new LinkedHashMap<>();
        if (lines == null) {
            return entries;
        }
        for (String line : lines) {
            parseLine(line).ifPresent(parsed -> entries.put(parsed.id(), parsed.entry()));
        }
        return entries;
    }

    private Optional<ParsedLine> parseLine(String line) {
        String[] parts = line.split("\\|", 3);
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            if (parts.length == 2) {
                return Optional.of(new ParsedLine(parts[0], new StoredEntry("", Integer.parseInt(parts[1]))));
            }
            return Optional.of(new ParsedLine(parts[0], new StoredEntry(parts[1], Integer.parseInt(parts[2]))));
        } catch (NumberFormatException exception) {
            LOGGER.debug("Could not read a JabDrive metadata line", exception);
            return Optional.empty();
        }
    }

    public record StoredEntry(String contentHash, int version) {
    }

    private record ParsedLine(String id, StoredEntry entry) {
    }
}
