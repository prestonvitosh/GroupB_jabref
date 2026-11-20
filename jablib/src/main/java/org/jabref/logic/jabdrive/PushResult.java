package org.jabref.logic.jabdrive;

import java.util.List;

import org.jspecify.annotations.NullMarked;

/// The remote's answer to one push: which changes were stored and which were refused.
@NullMarked
public record PushResult(List<AcceptedUpdate> accepted, List<RejectedUpdate> rejected) {
    public PushResult {
        accepted = List.copyOf(accepted);
        rejected = List.copyOf(rejected);
    }
}
