package org.jabref.gui.cloudsync;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import javafx.beans.property.SimpleBooleanProperty;

import org.jabref.gui.DialogService;
import org.jabref.logic.util.CurrentThreadTaskExecutor;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@NullMarked
@ResourceLock("Localization.lang")
class SyncStatusViewModelTest {

    private static final Instant SYNC_TIME = Instant.parse("2026-01-02T03:04:05Z");
    private static final Clock CLOCK = Clock.fixed(SYNC_TIME, ZoneOffset.UTC);
    private static final SyncResult SUCCESS = new SyncResult(List.of());

    private final SyncManager syncManager = mock(SyncManager.class);
    private final ConnectivityMonitor connectivityMonitor = mock(ConnectivityMonitor.class);
    private final DialogService dialogService = mock(DialogService.class);
    private final SimpleBooleanProperty online = new SimpleBooleanProperty(true);

    @BeforeEach
    void setUp() {
        when(connectivityMonitor.onlineProperty()).thenReturn(online);
    }

    @Test
    void creationWhileOnlineIsIdleAndEnablesSyncNow() {
        SyncStatusViewModel viewModel = createViewModel();

        assertAll(
                () -> assertEquals(CloudSyncStatus.IDLE, viewModel.statusProperty().get()),
                () -> assertFalse(viewModel.syncNowDisabledProperty().get()));
    }

    @Test
    void syncNowShowsSyncingAndDisablesSyncNowWhileCallingManagerOnce() throws Exception {
        SyncStatusViewModel viewModel = createViewModel();
        when(syncManager.sync()).thenAnswer(_ -> {
            assertAll(
                    () -> assertEquals(CloudSyncStatus.SYNCING, viewModel.statusProperty().get()),
                    () -> assertTrue(viewModel.syncNowDisabledProperty().get()));
            return SUCCESS;
        });

        viewModel.syncNow();

        verify(syncManager, times(1)).sync();
    }

    @Test
    void successfulSyncRecordsTimeClearsPendingChangesAndEnablesSyncNow() throws Exception {
        when(syncManager.getPendingChangesCount()).thenReturn(3);
        when(syncManager.sync()).thenReturn(SUCCESS);
        SyncStatusViewModel viewModel = createViewModel();
        assertEquals(3, viewModel.pendingChangesProperty().get());

        viewModel.syncNow();

        assertAll(
                () -> assertEquals(CloudSyncStatus.SYNCED, viewModel.statusProperty().get()),
                () -> assertEquals(SYNC_TIME, viewModel.lastSyncedProperty().get()),
                () -> assertEquals(0, viewModel.pendingChangesProperty().get()),
                () -> assertFalse(viewModel.syncNowDisabledProperty().get()));
        verify(syncManager).sync();
    }

    @Test
    void syncWithConflictsShowsConflictStatusAndOpensDialogWithThoseConflicts() throws Exception {
        List<SyncConflict> conflicts = List.of(
                new SyncConflict("Smith2025", "Local title", "Remote title"),
                new SyncConflict("Jones2024", "Local author", "Remote author"));
        when(syncManager.sync()).thenReturn(new SyncResult(conflicts));
        SyncStatusViewModel viewModel = createViewModel();

        viewModel.syncNow();

        assertEquals(CloudSyncStatus.CONFLICT, viewModel.statusProperty().get());
        verify(dialogService).showChoiceDialogAndWait(anyString(), anyString(), anyString(), eq(conflicts));
    }

    @Test
    void failedSyncShowsErrorKeepsPendingChangesAndEnablesSyncNow() throws Exception {
        IOException failure = new IOException("Cloud sync failed");
        when(syncManager.getPendingChangesCount()).thenReturn(3);
        when(syncManager.sync()).thenThrow(failure);
        SyncStatusViewModel viewModel = createViewModel();

        viewModel.syncNow();

        assertAll(
                () -> assertEquals(CloudSyncStatus.ERROR, viewModel.statusProperty().get()),
                () -> assertEquals(3, viewModel.pendingChangesProperty().get()),
                () -> assertFalse(viewModel.syncNowDisabledProperty().get()));
        verify(dialogService).showErrorDialogAndWait(failure);
    }

    @Test
    void offlineStatusDisablesSyncNowAndPreventsSync() throws Exception {
        SyncStatusViewModel viewModel = createViewModel();

        online.set(false);
        viewModel.syncNow();

        assertAll(
                () -> assertEquals(CloudSyncStatus.OFFLINE, viewModel.statusProperty().get()),
                () -> assertTrue(viewModel.syncNowDisabledProperty().get()));
        verify(syncManager, never()).sync();
    }

    @Test
    void reconnectingWithPendingChangesAutomaticallySyncs() throws Exception {
        online.set(false);
        when(syncManager.getPendingChangesCount()).thenReturn(2);
        when(syncManager.sync()).thenReturn(SUCCESS);
        SyncStatusViewModel viewModel = createViewModel();
        verify(syncManager, never()).sync();

        online.set(true);

        verify(syncManager, times(1)).sync();
        assertEquals(CloudSyncStatus.SYNCED, viewModel.statusProperty().get());
    }

    @Test
    void reconnectingWithoutPendingChangesDoesNotSync() throws Exception {
        online.set(false);
        when(syncManager.getPendingChangesCount()).thenReturn(0);
        SyncStatusViewModel viewModel = createViewModel();

        online.set(true);

        verify(syncManager, never()).sync();
        assertAll(
                () -> assertEquals(CloudSyncStatus.IDLE, viewModel.statusProperty().get()),
                () -> assertFalse(viewModel.syncNowDisabledProperty().get()));
    }

    @Test
    void syncNowWhileAlreadySyncingDoesNotStartAnotherSync() throws Exception {
        SyncStatusViewModel viewModel = createViewModel();
        when(syncManager.sync()).thenAnswer(_ -> {
            verify(syncManager, times(1)).sync();
            viewModel.syncNow();
            verify(syncManager, times(1)).sync();
            return SUCCESS;
        });

        viewModel.syncNow();

        verify(syncManager, times(1)).sync();
    }

    @Test
    void goingOfflineDuringSyncLetsItFinishButKeepsOfflineStatusAndSyncNowDisabled() throws Exception {
        SyncStatusViewModel viewModel = createViewModel();
        when(syncManager.sync()).thenAnswer(_ -> {
            online.set(false);
            return SUCCESS;
        });

        viewModel.syncNow();

        verify(syncManager, times(1)).sync();
        assertAll(
                () -> assertEquals(SYNC_TIME, viewModel.lastSyncedProperty().get()),
                () -> assertEquals(CloudSyncStatus.OFFLINE, viewModel.statusProperty().get()),
                () -> assertTrue(viewModel.syncNowDisabledProperty().get()));
    }

    @Test
    void syncWithZeroConflictsIsSyncedAndDoesNotOpenConflictDialog() throws Exception {
        when(syncManager.sync()).thenReturn(SUCCESS);
        SyncStatusViewModel viewModel = createViewModel();

        viewModel.syncNow();

        assertEquals(CloudSyncStatus.SYNCED, viewModel.statusProperty().get());
        verify(dialogService, never()).showChoiceDialogAndWait(anyString(), anyString(), anyString(), anyCollection());
    }

    private SyncStatusViewModel createViewModel() {
        return new SyncStatusViewModel(syncManager, connectivityMonitor, dialogService, new CurrentThreadTaskExecutor(), CLOCK);
    }
}
