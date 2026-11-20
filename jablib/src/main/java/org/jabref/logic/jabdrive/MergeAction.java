package org.jabref.logic.jabdrive;

/// What to do with one remote document.
public enum MergeAction {
    REPLACE,
    CONFLICT,
    NO_OP,
    DELETE
}
