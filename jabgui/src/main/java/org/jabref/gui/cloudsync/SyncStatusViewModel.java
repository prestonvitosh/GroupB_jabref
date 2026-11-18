package org.jabref.gui.cloudsync;

import java.time.Clock;
import java.time.Instant;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import org.jabref.gui.DialogService;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.BackgroundTask;
import org.jabref.logic.util.TaskExecutor;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class SyncStatusViewModel {

    private final ReadOnlyObjectWrapper<CloudSyncStatus> status = new ReadOnlyObjectWrapper<>(CloudSyncStatus.IDLE);
    private final ReadOnlyBooleanWrapper syncNowDisabled = new ReadOnlyBooleanWrapper();
    private final ReadOnlyIntegerWrapper pendingChanges = new ReadOnlyIntegerWrapper();
    private final ReadOnlyObjectWrapper<@Nullable Instant> lastSynced = new ReadOnlyObjectWrapper<>();

    private final SyncManager syncManager;
    private final ReadOnlyBooleanProperty online;
    private final DialogService dialogService;
    private final TaskExecutor taskExecutor;
    private final Clock clock;

    private boolean syncing;

    public SyncStatusViewModel(SyncManager syncManager,
                               ConnectivityMonitor connectivityMonitor,
                               DialogService dialogService,
                               TaskExecutor taskExecutor,
                               Clock clock) {
        this.syncManager = syncManager;
        this.online = connectivityMonitor.onlineProperty();
        this.dialogService = dialogService;
        this.taskExecutor = taskExecutor;
        this.clock = clock;

        pendingChanges.set(syncManager.getPendingChangesCount());
        status.set(online.get() ? CloudSyncStatus.IDLE : CloudSyncStatus.OFFLINE);
        updateSyncNowDisabled();

        online.addListener((_, _, isOnline) -> onConnectivityChanged(isOnline));
    }

    /// Starts a synchronization in the background. Does nothing while offline or while a sync is already running.
    public void syncNow() {
        if (!online.get() || syncing) {
            return;
        }
        syncing = true;
        status.set(CloudSyncStatus.SYNCING);
        updateSyncNowDisabled();

        BackgroundTask.wrap(syncManager::sync)
                      .onSuccess(this::onSyncSucceeded)
                      .onFailure(this::onSyncFailed)
                      .executeWith(taskExecutor);
    }

    private void onSyncSucceeded(SyncResult result) {
        syncing = false;
        lastSynced.set(clock.instant());
        if (result.hasConflicts()) {
            status.set(CloudSyncStatus.CONFLICT);
        } else {
            pendingChanges.set(0);
            status.set(CloudSyncStatus.SYNCED);
        }
        finishSync();

        if (result.hasConflicts()) {
            dialogService.showChoiceDialogAndWait(
                    Localization.lang("Resolve conflicts"),
                    Localization.lang("Select a conflicting entry to resolve."),
                    Localization.lang("Resolve conflicts"),
                    result.conflicts());
        }
    }

    private void onSyncFailed(Exception exception) {
        syncing = false;
        status.set(CloudSyncStatus.ERROR);
        finishSync();
        dialogService.showErrorDialogAndWait(exception);
    }

    private void finishSync() {
        if (!online.get()) {
            status.set(CloudSyncStatus.OFFLINE);
        }
        updateSyncNowDisabled();
    }

    private void onConnectivityChanged(boolean isOnline) {
        if (!isOnline) {
            status.set(CloudSyncStatus.OFFLINE);
            updateSyncNowDisabled();
            return;
        }
        if (syncing) {
            status.set(CloudSyncStatus.SYNCING);
            updateSyncNowDisabled();
            return;
        }
        status.set(CloudSyncStatus.IDLE);
        pendingChanges.set(syncManager.getPendingChangesCount());
        updateSyncNowDisabled();
        if (pendingChanges.get() > 0) {
            syncNow();
        }
    }

    private void updateSyncNowDisabled() {
        syncNowDisabled.set(!online.get() || syncing);
    }

    public ReadOnlyObjectProperty<CloudSyncStatus> statusProperty() {
        return status.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty syncNowDisabledProperty() {
        return syncNowDisabled.getReadOnlyProperty();
    }

    public ReadOnlyIntegerProperty pendingChangesProperty() {
        return pendingChanges.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<@Nullable Instant> lastSyncedProperty() {
        return lastSynced.getReadOnlyProperty();
    }
}
