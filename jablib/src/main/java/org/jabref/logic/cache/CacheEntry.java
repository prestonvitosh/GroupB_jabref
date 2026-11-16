package org.jabref.logic.cache;

import java.time.Instant;
import java.util.Objects;

/**
 * A bibliography entry stored in the local cache.
 */
public final class CacheEntry {

    private final String key;
    private final String content;
    private final long version;
    private final Instant cachedAt;

    public CacheEntry(String key, String content, long version, Instant cachedAt) {
        this.key = Objects.requireNonNull(key);
        this.content = Objects.requireNonNull(content);
        this.version = version;
        this.cachedAt = Objects.requireNonNull(cachedAt);
    }

    public String getKey() {
        return key;
    }

    public String getContent() {
        return content;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }

    /**
     * Returns a copy of this entry with a new cached time.
     */
    public CacheEntry withCachedAt(Instant newCachedAt) {
        return new CacheEntry(key, content, version, newCachedAt);
    }
}
