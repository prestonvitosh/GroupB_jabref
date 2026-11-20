package org.jabref.logic.jabdrive;

import org.jabref.logic.JabRefException;

/// The remote stored the push, and the response did not reach the client.
public class SyncResponseLostException extends JabRefException {

    public SyncResponseLostException() {
        super("The remote accepted the push but the response was lost");
    }
}
