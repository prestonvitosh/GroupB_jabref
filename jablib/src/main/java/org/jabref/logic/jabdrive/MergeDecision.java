package org.jabref.logic.jabdrive;

import org.jspecify.annotations.NullMarked;

/// The planner's decision for one entry. `version` and `dirty` describe the local entry after the action.
@NullMarked
public record MergeDecision(MergeAction action, int version, boolean dirty) {
}
