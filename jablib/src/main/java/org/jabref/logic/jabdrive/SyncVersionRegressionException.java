package org.jabref.logic.jabdrive;

import org.jabref.logic.JabRefException;

/// The remote offered a version older than the one stored locally.
/// Versions only increase on the remote, so this means the two sides have diverged.
public class SyncVersionRegressionException extends JabRefException {

    public SyncVersionRegressionException(int localVersion, int remoteVersion) {
        super("Remote version " + remoteVersion + " is lower than local version " + localVersion);
    }
}
