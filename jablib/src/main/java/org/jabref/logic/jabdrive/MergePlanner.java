package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// Decides how one remote document combines with the local entry.
///
/// The remote is the only side that increases versions. A lower remote version is refused.
/// Taking the remote entry clears the dirty flag. Keeping the local entry adopts the remote
/// version and stays dirty, so the next push can send it.
@NullMarked
public final class MergePlanner {

    public MergeDecision plan(LocalEntryState local, SyncDocument remote) throws SyncVersionRegressionException {
        if (local.tombstone() && remote.tombstone()) {
            return new MergeDecision(MergeAction.NO_OP, local.version(), false);
        }
        if (remote.version() < local.version()) {
            throw new SyncVersionRegressionException(local.version(), remote.version());
        }
        if (remote.tombstone()) {
            if (local.dirty()) {
                return new MergeDecision(MergeAction.CONFLICT, local.version(), true);
            }
            return new MergeDecision(MergeAction.DELETE, remote.version(), false);
        }
        if (local.tombstone()) {
            if (remote.version() == local.version()) {
                return new MergeDecision(MergeAction.NO_OP, local.version(), false);
            }
            return new MergeDecision(MergeAction.CONFLICT, local.version(), true);
        }
        if (remote.version() > local.version()) {
            if (local.dirty()) {
                return new MergeDecision(MergeAction.CONFLICT, local.version(), true);
            }
            return new MergeDecision(MergeAction.REPLACE, remote.version(), false);
        }
        return new MergeDecision(MergeAction.NO_OP, local.version(), local.dirty());
    }

    public ConflictResolution takeRemote(int remoteVersion) {
        return new ConflictResolution(remoteVersion, false);
    }

    public ConflictResolution keepLocal(int remoteVersion) {
        return new ConflictResolution(remoteVersion, true);
    }
}
