package engine.network.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.network.config.NetworkConfig;
import engine.network.messages.s2c.EntityState;
import engine.network.messages.s2c.LevelState;
import engine.network.messages.s2c.SnapshotMessage;
import feature.entities.CharacterClass;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link ClientState}. */
public class ClientStateTest {

  /** Verifies client-side network entity tracking can be updated and cleared. */
  @Test
  public void networkSyncedEntityIdsTracksAndClearsEntities() {
    ClientState state = clientState();

    state.trackNetworkEntity(10);
    state.trackNetworkEntity(11);
    state.untrackNetworkEntity(10);

    assertEquals(Set.of(11), state.networkSyncedEntityIds());

    state.clearNetworkEntities();

    assertTrue(state.networkSyncedEntityIds().isEmpty());
  }

  /** Verifies server-side snapshot entity tracking follows the active full baseline window. */
  @Test
  public void knownSnapshotEntityIdsTracksFullBaselineAndDeltaEntities() {
    ClientState state = clientState();
    SnapshotMessage snapshot =
        new SnapshotMessage(
            20, List.of(EntityState.builder().entityId(1).build()), new LevelState(Set.of()));

    state.resetKnownSnapshotEntityIds(snapshot);
    state.trackKnownSnapshotEntityIds(List.of(2));

    assertEquals(Set.of(1, 2), state.knownSnapshotEntityIds());

    state.clearSnapshotBaseline();

    assertTrue(state.knownSnapshotEntityIds().isEmpty());
  }

  /** Verifies known entity tracking is reset when the acknowledged baseline changes. */
  @Test
  public void knownSnapshotEntityIdsResetWhenBaselineChanges() {
    ClientState state = clientState();
    SnapshotMessage firstBaseline =
        new SnapshotMessage(
            20,
            List.of(
                EntityState.builder().entityId(1).build(),
                EntityState.builder().entityId(2).build()),
            new LevelState(Set.of()));
    SnapshotMessage secondBaseline =
        new SnapshotMessage(
            21, List.of(EntityState.builder().entityId(1).build()), new LevelState(Set.of()));

    state.ensureKnownSnapshotEntityIdsForBaseline(firstBaseline);
    state.trackKnownSnapshotEntityIds(List.of(3));
    state.ensureKnownSnapshotEntityIdsForBaseline(secondBaseline);

    assertEquals(Set.of(1), state.knownSnapshotEntityIds());
  }

  /** Verifies known entity tracking is preserved while the acknowledged baseline is unchanged. */
  @Test
  public void knownSnapshotEntityIdsPreservedForSameBaseline() {
    ClientState state = clientState();
    SnapshotMessage baseline =
        new SnapshotMessage(
            20, List.of(EntityState.builder().entityId(1).build()), new LevelState(Set.of()));

    state.ensureKnownSnapshotEntityIdsForBaseline(baseline);
    state.trackKnownSnapshotEntityIds(List.of(2));
    state.ensureKnownSnapshotEntityIdsForBaseline(baseline);

    assertEquals(Set.of(1, 2), state.knownSnapshotEntityIds());
  }

  @Test
  public void sequencesCrossSignedBoundaryAndRejectOldInputs() {
    ClientState state = clientState();
    assertTrue(state.advanceProcessedSeq(Short.MAX_VALUE));
    assertEquals(Short.MIN_VALUE, state.expectedSeq());
    assertTrue(state.isSeqPlausible(Short.MIN_VALUE));
    assertTrue(state.advanceProcessedSeq(Short.MIN_VALUE));
    assertTrue(state.isSeqPlausible(Short.MIN_VALUE));
    assertFalse(state.advanceProcessedSeq(Short.MIN_VALUE));
    assertFalse(state.isSeqPlausible(Short.MAX_VALUE));
    assertFalse(state.advanceProcessedSeq(Short.MAX_VALUE));
    assertEquals(Short.MIN_VALUE, state.lastProcessedSeq());
    assertTrue(state.advanceProcessedSeq(Short.MIN_VALUE + 1));
  }

  @Test
  public void freshAndResetStatesAcceptAnyFirstSequence() {
    for (int first : new int[] {0, -1, -123, Short.MIN_VALUE, Short.MAX_VALUE}) {
      ClientState state = clientState();
      for (int attempt = 0; attempt < 2; attempt++) {
        assertTrue(state.isSeqPlausible(first));
        assertTrue(state.advanceProcessedSeq(first));
        assertFalse(state.advanceProcessedSeq(first));
        assertFalse(state.isSeqPlausible((short) (first - 1)));
        assertTrue(state.advanceProcessedSeq((short) (first + 1)));
        state.resetForReconnect(2, new byte[] {4}, false);
      }
    }
  }

  @Test
  public void lostInputBurstsDoNotBlockLaterInputs() {
    for (int last : new int[] {10, Short.MAX_VALUE, -1}) {
      ClientState state = clientState();
      assertTrue(state.advanceProcessedSeq(last));
      int afterLoss = (short) (last + NetworkConfig.MAX_SEQUENCE_GAP + 500);
      assertTrue(state.isSeqPlausible(afterLoss));
      assertTrue(state.advanceProcessedSeq(afterLoss));
      assertFalse(state.isSeqPlausible((short) (afterLoss - 1)));
      assertFalse(state.isSeqPlausible((short) (afterLoss + 32768)));
    }
  }

  @Test
  public void gapHandlingUsesWrappedExpectedSequence() {
    ClientState state = clientState();
    state.advanceProcessedSeq(Short.MAX_VALUE - 1);
    assertEquals(1, state.handleSeqGap(Short.MIN_VALUE));
    assertEquals(Short.MIN_VALUE, state.expectedSeq());
    assertThrows(IllegalArgumentException.class, () -> state.handleSeqGap(Short.MIN_VALUE));
    assertThrows(IllegalArgumentException.class, () -> state.handleSeqGap(Short.MAX_VALUE));
    assertThrows(
        IllegalArgumentException.class,
        () -> state.handleSeqGap(Short.MIN_VALUE + NetworkConfig.MAX_SEQUENCE_GAP + 1));
    assertEquals(
        NetworkConfig.MAX_SEQUENCE_GAP,
        state.handleSeqGap(Short.MIN_VALUE + NetworkConfig.MAX_SEQUENCE_GAP));
  }

  @Test
  public void sequenceWarningsAreLimitedPerClientIncludingReconnects() {
    ClientState state = clientState();
    long now = -100;
    assertTrue(state.shouldLogSequenceWarning(now));
    assertFalse(state.shouldLogSequenceWarning(now));
    assertFalse(state.shouldLogSequenceWarning(now + 9_999_999_999L));
    assertTrue(clientState().shouldLogSequenceWarning(now));
    state.resetForReconnect(2, new byte[] {4}, false);
    assertFalse(state.shouldLogSequenceWarning(now + 9_999_999_999L));
    assertTrue(state.shouldLogSequenceWarning(now + 10_000_000_000L));
    assertFalse(state.shouldLogSequenceWarning(now + 10_000_000_001L));
  }

  private static ClientState clientState() {
    return new ClientState((short) 1, "tester", 1, new byte[] {1, 2, 3}, CharacterClass.WIZARD);
  }
}
