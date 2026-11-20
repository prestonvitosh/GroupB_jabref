package org.jabref.logic.jabdrive;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MergePlannerTest {

    private final MergePlanner planner = new MergePlanner();

    @ParameterizedTest
    @MethodSource("merges")
    void plansMerge(LocalEntryState local, int remoteVersion, boolean remoteTombstone, MergeAction action, int version, boolean dirty) throws SyncVersionRegressionException {
        SyncDocument remote = new SyncDocument("id", remoteVersion, "hash", "article", "", Map.of(), remoteTombstone);
        MergeDecision decision = planner.plan(local, remote);
        assertEquals(action, decision.action());
        assertEquals(version, decision.version());
        assertEquals(dirty, decision.dirty());
    }

    @Test
    void planRejectsLowerRemoteVersion() {
        LocalEntryState local = new LocalEntryState(5, false, false);
        SyncDocument remote = new SyncDocument("id", 3, "hash", "article", "", Map.of(), false);
        assertThrows(SyncVersionRegressionException.class, () -> planner.plan(local, remote));
    }

    @Test
    void takeRemoteClearsDirtyAndAdoptsRemoteVersion() {
        ConflictResolution resolution = planner.takeRemote(4);
        assertEquals(4, resolution.version());
        assertEquals(false, resolution.dirty());
    }

    @Test
    void keepLocalStaysDirtyAndAdoptsRemoteVersion() {
        ConflictResolution resolution = planner.keepLocal(4);
        assertEquals(4, resolution.version());
        assertEquals(true, resolution.dirty());
    }

    private static Stream<Arguments> merges() {
        return Stream.of(
                Arguments.of(new LocalEntryState(1, false, false), 2, false, MergeAction.REPLACE, 2, false),
                Arguments.of(new LocalEntryState(1, true, false), 4, false, MergeAction.CONFLICT, 1, true),
                Arguments.of(new LocalEntryState(2, false, false), 2, false, MergeAction.NO_OP, 2, false),
                Arguments.of(new LocalEntryState(1, false, false), 3, true, MergeAction.DELETE, 3, false),
                Arguments.of(new LocalEntryState(1, false, true), 2, true, MergeAction.NO_OP, 1, false),
                Arguments.of(new LocalEntryState(1, true, false), 3, true, MergeAction.CONFLICT, 1, true)
        );
    }
}
