package net.hasht.app;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class PushOrderTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String ORIGIN = "https://chat.test";
    private static final String TAG = "channel:a";
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";

    private static boolean event(PushOrder.State state, String kind, String id, long created, long version) {
        return state.accept(ORIGIN, TAG, kind, Collections.singletonList(id), created, version, NOW, NOW);
    }
    private static PushOrder.State restart(PushOrder.State state) throws Exception {
        return PushOrder.State.decode(state.encode());
    }

    @Test public void batchedRecallBeforeAlertsProtectsEveryIdentityAcrossRestart() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(state.acceptNotification(ORIGIN, TAG + "/message:" + B, "remove", Arrays.asList(A, B), 0, 0, NOW, NOW));
        state = restart(state);
        assertFalse(state.acceptNotification(ORIGIN, TAG + "/message:" + A, "message", Collections.singletonList(A), 1, 1, NOW, NOW));
        assertFalse(state.acceptNotification(ORIGIN, TAG + "/message:" + B, "message", Collections.singletonList(B), 2, 2, NOW, NOW));
        assertTrue(state.acceptNotification(ORIGIN, TAG + "/message:new", "message", Collections.singletonList("new"), 3, 3, NOW, NOW));
    }

    @Test public void individualMessagesSurviveReorderingAndChannelClear() throws Exception {
        PushOrder.State state = new PushOrder.State();
        String first = TAG + "/message:" + A;
        String second = TAG + "/message:" + B;
        assertTrue(state.accept(ORIGIN, second, "message", Collections.singletonList(B), 2, 2, NOW, NOW));
        assertTrue(state.accept(ORIGIN, first, "message", Collections.singletonList(A), 1, 1, NOW, NOW));
        assertTrue(state.accept(ORIGIN, first, "remove", Collections.singletonList(A), 1, 1, NOW, NOW));
        state = restart(state);
        assertFalse(state.accept(ORIGIN, first, "message", Collections.singletonList(A), 1, 1, NOW, NOW));
        assertTrue(state.accept(ORIGIN, second, "update", Collections.singletonList(B), 2, 3, NOW, NOW));
        state.clear(ORIGIN, TAG, NOW);
        state = restart(state);
        assertFalse(state.accept(ORIGIN, second, "update", Collections.singletonList(B), 2, 4, NOW, NOW));
        assertFalse(state.accept(ORIGIN, TAG + "/message:new", "message", Collections.singletonList("new"), 3, 3, NOW, NOW));
        assertTrue(state.accept(ORIGIN, TAG + "/message:new", "message", Collections.singletonList("new"), 3, 3, NOW + 1, NOW + 1));
    }

    @Test public void newerChannelMessageWinsAcrossRestartAndOutOfOrderEdits() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(event(state, "message", A, 1, 1));
        assertTrue(event(state, "message", B, 2, 2));
        state = restart(state);
        assertFalse(event(state, "update", A, 1, 50));
        assertFalse(event(state, "message", A, 1, 1));
        assertTrue(event(state, "update", B, 2, 5));
        assertFalse(event(state, "update", B, 2, 3));
        assertFalse(event(state, "message", B, 2, 2));
        assertFalse(event(state, "update", B, 2, 5));
    }

    @Test public void updateFirstShowsLatestAndSuppressesItsDelayedOriginal() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(event(state, "update", A, 1, 5));
        state = restart(state);
        assertFalse(event(state, "message", A, 1, 1));
        assertTrue(event(state, "update", A, 1, 6));
    }

    @Test public void removeIsTerminalEvenWhenItsEditVersionIsOld() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(event(state, "update", A, 1, 9));
        assertTrue(event(state, "remove", A, 1, 1));
        state = restart(state);
        assertFalse(event(state, "message", A, 1, 1));
        assertFalse(event(state, "update", A, 1, 10));
        assertTrue(event(state, "message", B, 2, 2));
        assertTrue(event(state, "remove", A, 1, 1));
        assertTrue(event(state, "update", B, 2, 3));
    }

    @Test public void removeBeforeAlertAndLegacyRemoveBothSurviveRestart() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(event(state, "remove", A, 1, 1));
        assertFalse(event(restart(state), "message", A, 1, 1));
        state = new PushOrder.State();
        assertTrue(state.accept(ORIGIN, TAG, "remove", Collections.singletonList(A), 0, 0, 0, NOW));
        assertFalse(event(restart(state), "message", A, 1, 1));
    }

    @Test public void fiveChannelsAndFiveOriginsOnOneDeviceAreIndependent() throws Exception {
        PushOrder.State state = new PushOrder.State();
        for (int origin = 0; origin < 5; origin++) {
            for (int channel = 0; channel < 5; channel++) {
                assertTrue(state.accept("https://instance" + origin + ".test", "channel:" + channel,
                    "message", Collections.singletonList(A), 1, 1, NOW, NOW));
            }
        }
        assertEquals(25, restart(state).channels.size());
        state.forget("https://instance0.test");
        assertEquals(20, restart(state).channels.size());
    }

    @Test public void overflowPersistsRecoveryButAllowsFreshOrderedEvents() throws Exception {
        PushOrder.State state = new PushOrder.State();
        state.overflow(Arrays.asList(ORIGIN, "https://other.test"), NOW);
        state = restart(state); // Also covers permission denial: flag is independent of display.
        long generation = state.recoveryGeneration(ORIGIN);
        assertTrue(generation > 0);
        assertFalse(event(state, "message", A, 1, 1));
        assertFalse(state.accept(ORIGIN, TAG, "message", Collections.singletonList(A), 0, 0, 0, NOW + 1));
        assertTrue(state.accept(ORIGIN, TAG, "message", Collections.singletonList(B), 2, 2, NOW + 1, NOW + 1));
        assertEquals(generation, state.recoveryGeneration(ORIGIN));
        assertFalse(state.complete(ORIGIN, generation + 1));
        assertTrue(state.complete(ORIGIN, generation));
        assertEquals(0, state.recoveryGeneration(ORIGIN));
        assertTrue(state.recoveryGeneration("https://other.test") > 0);
        assertTrue(state.accept(ORIGIN, TAG, "update", Collections.singletonList(B), 2, 3, NOW + 2, NOW + 10));
        state.overflow(Collections.singletonList(ORIGIN), NOW + 20);
        assertFalse(state.complete(ORIGIN, generation));
        state.forget(ORIGIN);
        assertEquals(0, state.recoveryGeneration(ORIGIN));
        assertFalse(state.cutoffs.containsKey(ORIGIN));
    }

    @Test public void perOriginCapacityProtectsOtherInstancesAndDoesNotEvictTombstones() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(state.accept("https://other.test", TAG, "message", Collections.singletonList(A), 1, 1, NOW, NOW));
        for (int i = 0; i < PushOrder.MAX_ORIGIN_CHANNELS; i++) {
            assertTrue(state.accept(ORIGIN, "channel:" + i, "remove", Collections.singletonList(A), 1, 1, NOW, NOW));
        }
        assertFalse(state.accept(ORIGIN, "channel:overflow", "message", Collections.singletonList(B), 2, 2, NOW, NOW));
        assertEquals(1, state.channels.size());
        assertTrue(state.recoveryGeneration(ORIGIN) > 0);
        assertEquals(0, state.recoveryGeneration("https://other.test"));
        assertFalse(state.accept(ORIGIN, "channel:0", "message", Collections.singletonList(A), 1, 1, NOW, NOW));
        assertEquals(1, restart(state).channels.size());
    }

    @Test public void unrelatedOverflowDoesNotClearFreshAlertsForAnOriginStillRecovering() throws Exception {
        String other = "https://other.test";
        PushOrder.State state = new PushOrder.State();
        state.overflow(Collections.singletonList(other), NOW);
        long otherGeneration = state.recoveryGeneration(other);
        assertTrue(state.accept(other, TAG, "message", Collections.singletonList(B), 2, 2, NOW + 1, NOW + 1));
        for (int i = 0; i < PushOrder.MAX_ORIGIN_CHANNELS; i++) {
            assertTrue(state.accept(ORIGIN, "channel:" + i, "message", Collections.singletonList(A), 1, 1, NOW, NOW + 1));
        }
        assertFalse(state.accept(ORIGIN, "channel:overflow", "message", Collections.singletonList(A), 1, 1, NOW + 2, NOW + 2));
        state = restart(state);
        // Production uses this exact affected set for notification cancellation.
        assertEquals(Collections.singleton(ORIGIN), state.affectedOrigins());
        assertEquals(otherGeneration, state.recoveryGeneration(other));
        assertTrue(state.accept(other, TAG, "update", Collections.singletonList(B), 2, 3, NOW + 3, NOW + 3));
    }

    @Test public void globalCapacityRecoversContributingOriginsAndFreesSpace() {
        PushOrder.State state = new PushOrder.State();
        for (int i = 0; i < PushOrder.MAX_CHANNELS; i++) {
            assertTrue(state.accept("https://instance" + (i / PushOrder.MAX_ORIGIN_CHANNELS) + ".test",
                "channel:" + i, "message", Collections.singletonList(A), 1, 1, NOW, NOW));
        }
        assertFalse(state.accept(ORIGIN, TAG, "message", Collections.singletonList(B), 2, 2, NOW, NOW));
        assertTrue(state.channels.isEmpty());
        assertTrue(state.recoveryGeneration("https://instance0.test") > 0);
        assertTrue(state.accept(ORIGIN, TAG, "message", Collections.singletonList(B), 2, 2, NOW + 1, NOW + 1));
    }

    @Test public void ttlAndLocalReadCutoffPreventResurrectionAfterPruning() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(event(state, "message", A, 1, 1));
        state.clear(ORIGIN, TAG, NOW);
        assertFalse(event(restart(state), "update", A, 1, 5));
        long later = NOW + PushOrder.RETENTION + 1;
        assertFalse(state.accept(ORIGIN, TAG, "message", Collections.singletonList(A), 1, 1, NOW, later));
        assertTrue(state.channels.isEmpty());
        assertTrue(state.accept(ORIGIN, TAG, "message", Collections.singletonList(B), 2, 2, later, later));
    }

    @Test public void legacyCatchUpCanCarryMoreIdsThanThePersistedTombstoneLimit() throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 66; i++) ids.add(String.format("00000000-0000-0000-0000-%012d", i));
        PushOrder.State state = new PushOrder.State();
        assertTrue(state.accept(ORIGIN, TAG, "message", ids, 0, 0, 0, NOW));
        state = restart(state);
        assertEquals(ids.get(65), state.channels.values().iterator().next().id);
        assertFalse(state.accept(ORIGIN, TAG, "remove", ids, 0, 0, 0, NOW));
        assertTrue(state.recoveryGeneration(ORIGIN) > 0);
    }

    @Test public void equalTimestampsUseUuidOrderAndLegacyTombstoneCapRecoversSafely() throws Exception {
        PushOrder.State state = new PushOrder.State();
        assertTrue(event(state, "message", B, 1, 1));
        assertFalse(event(state, "message", A, 1, 1));
        state = new PushOrder.State();
        for (int i = 0; i < PushOrder.MAX_TOMBSTONES; i++) {
            assertTrue(state.accept(ORIGIN, TAG, "remove", Collections.singletonList("id" + i), 0, 0, 0, NOW));
        }
        assertFalse(state.accept(ORIGIN, TAG, "remove", Collections.singletonList("extra"), 0, 0, 0, NOW));
        assertTrue(restart(state).recoveryGeneration(ORIGIN) > 0);
        assertFalse(event(state, "message", A, 1, 1));
    }
}
