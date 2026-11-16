package org.jabref.logic.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheManagerTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(10);
    private EntrySource source;
    private Clock clock;
    private CacheManager manager;

    @BeforeEach
    void setUp() {
        source = mock(EntrySource.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        manager = new CacheManager(source, clock, MAX_AGE, 2);
    }

    private void stubEntry(String key, String content, long version) {
        when(source.load(key)).thenReturn(new CacheEntry(key, content, version, Instant.EPOCH));
    }

    @Test
    void freshEntryIsReturnedWithoutCallingSource() {
        stubEntry("a", "original", 1);
        when(source.getCurrentVersion("a")).thenReturn(1L);
        CacheEntry original = manager.getEntry("a");
        when(clock.instant()).thenReturn(START.plusSeconds(1));
        clearInvocations(source);

        assertSame(original, manager.getEntry("a"));
        // Checking the version is allowed by rule 2. Rule 1 means the entry must not be reloaded.
        verify(source, never()).load("a");
    }

    @Test
    void expiredEntryIsReloadedAndReplacesCachedEntry() {
        assertReloadAt(MAX_AGE.plusNanos(1));
    }

    @Test
    void changedSourceVersionIsReloadedAndReplacesCachedEntry() {
        stubEntry("a", "original", 1);
        manager.getEntry("a");
        stubEntry("a", "updated", 2);
        when(source.getCurrentVersion("a")).thenReturn(2L);
        when(clock.instant()).thenReturn(START.plusSeconds(1));

        CacheEntry updated = manager.getEntry("a");

        assertEntry(updated, "a", "updated", 2, START.plusSeconds(1));
        assertSame(updated, manager.getEntry("a"));
        assertEquals(1, manager.size());
        verify(source, times(2)).load("a");
    }

    @Test
    void missingEntryIsLoadedStoredAndReturned() {
        stubEntry("a", "loaded", 1);
        when(source.getCurrentVersion("a")).thenReturn(1L);

        CacheEntry loaded = manager.getEntry("a");

        assertEntry(loaded, "a", "loaded", 1, START);
        assertTrue(manager.contains("a"));
        assertEquals(1, manager.size());
        assertSame(loaded, manager.getEntry("a"));
        verify(source).load("a");
    }

    @Test
    void fullCacheEvictsLeastRecentlyUsedEntry() {
        stubEntry("a", "a", 1);
        stubEntry("b", "b", 1);
        stubEntry("c", "c", 1);
        when(source.getCurrentVersion("a")).thenReturn(1L);
        manager.getEntry("a");
        manager.getEntry("b");
        manager.getEntry("a");

        assertNotNull(manager.getEntry("c"));

        assertTrue(manager.contains("a"));
        assertFalse(manager.contains("b"));
        assertTrue(manager.contains("c"));
        assertEquals(2, manager.size());
        verify(source).load("a");
        verify(source).load("b");
        verify(source).load("c");
    }

    @Test
    void nullAndEmptyKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.getEntry(null));
        assertThrows(IllegalArgumentException.class, () -> manager.getEntry(""));
        verifyNoInteractions(source);
        assertEquals(0, manager.size());
    }

    @Test
    void entryExactlyAtMaxAgeIsReloaded() {
        assertReloadAt(MAX_AGE);
    }

    private void assertReloadAt(Duration age) {
        stubEntry("a", "original", 1);
        manager.getEntry("a");
        stubEntry("a", "updated", 1);
        when(source.getCurrentVersion("a")).thenReturn(1L);
        Instant reloadTime = START.plus(age);
        when(clock.instant()).thenReturn(reloadTime);

        CacheEntry updated = manager.getEntry("a");

        assertEntry(updated, "a", "updated", 1, reloadTime);
        assertSame(updated, manager.getEntry("a"));
        assertEquals(1, manager.size());
        verify(source, times(2)).load("a");
    }

    @Test
    void sizeLimitOneEvictsPreviousEntryAndReloadsItOnNextAccess() {
        manager = new CacheManager(source, clock, MAX_AGE, 1);
        stubEntry("a", "a", 1);
        stubEntry("b", "b", 1);
        manager.getEntry("a");

        assertNotNull(manager.getEntry("b"));
        assertFalse(manager.contains("a"));
        assertTrue(manager.contains("b"));
        assertEquals(1, manager.size());

        assertNotNull(manager.getEntry("a"));
        assertTrue(manager.contains("a"));
        assertFalse(manager.contains("b"));
        assertEquals(1, manager.size());
        verify(source, times(2)).load("a");
    }

    @Test
    void missingSourceEntryThrowsInsteadOfReturningNull() {
        EntryNotFoundException failure = new EntryNotFoundException("missing");
        when(source.load("missing")).thenThrow(failure);

        assertSame(failure, assertThrows(EntryNotFoundException.class,
                () -> manager.getEntry("missing")));
        assertFalse(manager.contains("missing"));
        assertEquals(0, manager.size());
    }

    private static void assertEntry(CacheEntry entry, String key, String content,
                                    long version, Instant cachedAt) {
        assertNotNull(entry);
        assertEquals(key, entry.getKey());
        assertEquals(content, entry.getContent());
        assertEquals(version, entry.getVersion());
        assertEquals(cachedAt, entry.getCachedAt());
    }
}
