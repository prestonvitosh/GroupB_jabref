package org.jabref.logic.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Local cache for bibliography entries.
 *
 * Fresh entries are returned without reloading. Expired or outdated entries are
 * reloaded. When the cache is full, the least recently used entry is removed.
 */
@NullMarked
public class CacheManager {

    private final EntrySource source;
    private final Clock clock;
    private final Duration maxAge;
    private final int maxSize;
    private final LinkedHashMap<String, CacheEntry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public CacheManager(EntrySource source, Clock clock, Duration maxAge, int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("Maximum cache size must be at least one");
        }
        this.source = source;
        this.clock = clock;
        this.maxAge = maxAge;
        this.maxSize = maxSize;
    }

    public CacheEntry getEntry(@Nullable String key) {
        key = validateKey(key);
        Instant now = clock.instant();
        CacheEntry cached = entries.get(key);
        if (cached != null
                && Duration.between(cached.getCachedAt(), now).compareTo(maxAge) < 0
                && cached.getVersion() == source.getCurrentVersion(key)) {
            return cached;
        }

        CacheEntry loaded = source.load(key).withCachedAt(now);
        if (!entries.containsKey(key) && entries.size() >= maxSize) {
            entries.pollFirstEntry();
        }
        entries.put(key, loaded);
        return loaded;
    }

    public boolean contains(@Nullable String key) {
        key = validateKey(key);
        return entries.containsKey(key);
    }

    public int size() {
        return entries.size();
    }

    private static String validateKey(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Cache key must not be null or empty");
        }
        return key;
    }
}
