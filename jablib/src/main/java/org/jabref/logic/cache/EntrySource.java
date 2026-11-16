package org.jabref.logic.cache;

/**
 * The source of truth for bibliography entries, such as the local database or a remote library.
 */
public interface EntrySource {

    /**
     * Loads the latest version of the entry with the given key.
     * Throws EntryNotFoundException if the source has no entry with that key.
     */
    CacheEntry load(String key);

    /**
     * Returns the current version number of the entry in the source.
     */
    long getCurrentVersion(String key);
}
