package org.jabref.logic.cache;

public class EntryNotFoundException extends RuntimeException {

    public EntryNotFoundException(String key) {
        super("No entry found for key: " + key);
    }
}
