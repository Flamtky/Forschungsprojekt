package rooms.lasthour.adaptation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.network.server.Session;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Action;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.FrustrationRisk;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Input;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Judgment;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.SupportState;
import rooms.lasthour.util.LastHourPuzzle;

class LastHourAdaptationPolicyTest {

  private static final LastHourAdaptationPolicy.Thresholds THRESHOLDS =
      new LastHourAdaptationPolicy.Thresholds(10, 5, 0.5);

  @Test
  void repeatedPowerStartDoesNotResetEpisodeClock() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    assertEquals(SupportState.B0, policy.supportState(LastHourEpisode.E1, 270));
    policy.started(LastHourPuzzle.POWER, 270);
    assertEquals(SupportState.B0, policy.supportState(LastHourEpisode.E1, 279));
    policy.started(LastHourPuzzle.POWER, 279);
    assertEquals(SupportState.B1, policy.supportState(LastHourEpisode.E1, 280));
  }

  @Test
  void mapsOnlyTheSixResearchEpisodes() {
    assertEquals(LastHourEpisode.E1, LastHourEpisode.forPuzzle(LastHourPuzzle.POWER).orElseThrow());
    assertEquals(
        LastHourEpisode.E3,
        LastHourEpisode.forPuzzle(LastHourPuzzle.STORAGE_RECOVERY).orElseThrow());
    assertEquals(
        LastHourEpisode.E3, LastHourEpisode.forPuzzle(LastHourPuzzle.STORAGE_ACCESS).orElseThrow());
    assertTrue(LastHourEpisode.forPuzzle(LastHourPuzzle.VIRUS_NEUTRALIZATION).isEmpty());
  }

  @Test
  void timeGatesAndJevStuckJudgmentMoveFromB0ThroughB2() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.POWER, 0);
    policy.action(LastHourPuzzle.POWER, "pc", "power-button", participant, 1);

    assertEquals(SupportState.B0, policy.supportState(LastHourEpisode.E1, 9));
    assertEquals(SupportState.B1, policy.supportState(LastHourEpisode.E1, 10));

    judge(policy, LastHourEpisode.E1, 0.3, Map.of(), 10);
    assertEquals(SupportState.B1, policy.supportState(LastHourEpisode.E1, 10));

    policy.action(LastHourPuzzle.POWER, "pc", "power-button", participant, 10);
    judge(policy, LastHourEpisode.E1, 0.9, Map.of(), 10);

    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E1, 10));
    assertEquals(
        Action.HINT_OFFER,
        policy.nextDecision(LastHourEpisode.E1, 10, true).orElseThrow().action());
    assertTrue(policy.nextDecision(LastHourEpisode.E1, 20, true).isEmpty());
  }

  @Test
  void progressInAnotherEpisodeRestartsTheWaitingTime() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.LOGIN, 0);
    wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "wrong", 1);
    judge(policy, LastHourEpisode.E2, 0.9, Map.of(), 10);
    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E2, 10));

    policy.solved(LastHourPuzzle.POWER, 50);

    assertEquals(SupportState.B0, policy.supportState(LastHourEpisode.E2, 55));
    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E2, 61));
    assertEquals(
        "less than 1 minute",
        policy.observation(LastHourEpisode.E2, 61).orElseThrow().timeWithoutProgress());
  }

  @Test
  void pureInactivityIsNeverJudged() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    policy.started(LastHourPuzzle.LOGIN, 0);

    assertTrue(policy.observation(LastHourEpisode.E2, 600).isEmpty());
    assertEquals(SupportState.B1, policy.supportState(LastHourEpisode.E2, 600));
  }

  @Test
  void judgmentOfAnOutdatedObservationIsIgnored() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.LOGIN, 0);
    wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "a", 1);
    var beforeNewInput = policy.observation(LastHourEpisode.E2, 30).orElseThrow();
    var beforeNextMinute = policy.observation(LastHourEpisode.E2, 59).orElseThrow();

    assertFalse(policy.judged(beforeNextMinute, new Judgment(0.9, Map.of()), 60));
    wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "b", 31);
    assertFalse(policy.judged(beforeNewInput, new Judgment(0.9, Map.of()), 31));
    assertEquals(FrustrationRisk.NONE, policy.risks(LastHourEpisode.E2).get(participant));
  }

  @Test
  void newInputWaitsForItsOwnJudgmentWhileANewTimeBucketDoesNot() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.LOGIN, 0);
    wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "a", 1);
    judge(policy, LastHourEpisode.E2, 0.9, Map.of(), 5);

    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E2, 70));

    wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "b", 71);
    assertEquals(SupportState.B1, policy.supportState(LastHourEpisode.E2, 71));
    judge(policy, LastHourEpisode.E2, 0.9, Map.of(), 72);
    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E2, 72));
  }

  @Test
  void inputThatLeavesTheRecentInputsUnchangedKeepsTheJudgment() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.LOGIN, 0);
    int inputs = LastHourAdaptationPolicy.MAX_INPUTS_PER_PERSON + 1;
    long spacing = LastHourAdaptationPolicy.DUPLICATE_INPUT_SECONDS + 1;
    for (int input = 1; input <= inputs; input++) {
      wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "admin", input * spacing);
    }
    judge(policy, LastHourEpisode.E2, 0.9, Map.of(), inputs * spacing);

    wrongAttempt(policy, LastHourPuzzle.LOGIN, participant, "admin", (inputs + 1) * spacing);

    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E2, 120));
  }

  @Test
  void hammeringTheSameInputCountsOnce() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.STORAGE_ACCESS, 0);
    for (long second : new long[] {10, 10, 11, 11}) {
      wrongAttempt(policy, participant, "8143", second);
    }
    assertEquals(
        1,
        policy.observation(LastHourEpisode.E3, 12).orElseThrow().inputs().get(participant).size());

    wrongAttempt(
        policy, participant, "8143", 11 + LastHourAdaptationPolicy.DUPLICATE_INPUT_SECONDS + 1);

    var inputs = policy.observation(LastHourEpisode.E3, 30).orElseThrow().inputs().get(participant);
    assertEquals(2, inputs.size());
    assertTrue(inputs.getLast().repeated());
  }

  @Test
  void observationOfANewPhaseNeverEqualsTheOldOne() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.STORAGE_RECOVERY, 0);
    wrongAttempt(policy, LastHourPuzzle.STORAGE_RECOVERY, participant, "1111", 1);
    var before = policy.observation(LastHourEpisode.E3, 5).orElseThrow();

    policy.attempt(LastHourPuzzle.STORAGE_RECOVERY, "recovery", "ok", true, participant, 6);
    wrongAttempt(policy, LastHourPuzzle.STORAGE_RECOVERY, participant, "1111", 7);

    assertNotEquals(before, policy.observation(LastHourEpisode.E3, 11).orElseThrow());
  }

  @Test
  void observationMarksRepetitionAndPacePerPerson() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID observer = UUID.randomUUID();
    policy.started(LastHourPuzzle.STORAGE_ACCESS, 0);
    policy.registerParticipant(LastHourEpisode.E3, observer);
    wrongAttempt(policy, first, "1111", 10);
    wrongAttempt(policy, second, "1111", 12);
    wrongAttempt(policy, first, "2222", 25);
    for (int i = 0; i < 10; i++) {
      wrongAttempt(policy, second, String.valueOf(3000 + i), 100 + i);
    }

    var observation = policy.observation(LastHourEpisode.E3, 150).orElseThrow();

    assertEquals(List.of(observer, first, second), List.copyOf(observation.inputs().keySet()));
    assertEquals(List.of(), observation.inputs().get(observer));
    assertEquals(
        List.of(
            new Input("attempt", "storage-keypad", "1111", false, false),
            new Input("attempt", "storage-keypad", "2222", false, true)),
        observation.inputs().get(first));
    assertEquals(
        LastHourAdaptationPolicy.MAX_INPUTS_PER_PERSON, observation.inputs().get(second).size());
    assertEquals("3009", observation.inputs().get(second).getLast().value());
    assertEquals("2 to 5 minutes", observation.timeWithoutProgress());
  }

  @Test
  void e3EscalationNeedsFreshStuckJudgmentsAfterEachSupport() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.STORAGE_ACCESS, 0);
    wrongAttempt(policy, participant, "1111", 1);
    judge(policy, LastHourEpisode.E3, 0.9, Map.of(), 10);
    assertEquals(
        Action.HINT_OFFER,
        policy.nextDecision(LastHourEpisode.E3, 10, true).orElseThrow().action());

    assertTrue(policy.hintAccepted(LastHourEpisode.E3, 10));
    assertEquals(SupportState.B1, policy.supportState(LastHourEpisode.E3, 15));

    wrongAttempt(policy, participant, "2222", 11);
    judge(policy, LastHourEpisode.E3, 0.9, Map.of(), 15);
    assertEquals(SupportState.B3, policy.supportState(LastHourEpisode.E3, 15));
    assertEquals(
        Action.STORAGE_SIMPLIFY,
        policy.nextDecision(LastHourEpisode.E3, 15, true).orElseThrow().action());
    assertTrue(policy.nextDecision(LastHourEpisode.E3, 20, true).isEmpty());

    wrongAttempt(policy, participant, "3333", 16);
    judge(policy, LastHourEpisode.E3, 0.9, Map.of(), 20);
    assertEquals(
        Action.STORAGE_AUTO_COMPLETE,
        policy.nextDecision(LastHourEpisode.E3, 20, true).orElseThrow().action());
  }

  @Test
  void progressAfterAcceptedHintEndsTheEscalationChain() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.STORAGE_ACCESS, 0);
    wrongAttempt(policy, participant, "1111", 1);
    judge(policy, LastHourEpisode.E3, 0.9, Map.of(), 10);
    assertEquals(
        Action.HINT_OFFER,
        policy.nextDecision(LastHourEpisode.E3, 10, true).orElseThrow().action());
    assertTrue(policy.hintAccepted(LastHourEpisode.E3, 10));

    policy.attempt(
        LastHourPuzzle.STORAGE_RECOVERY, "storage-recovery", "correct", true, participant, 11);
    wrongAttempt(policy, participant, "2222", 12);
    judge(policy, LastHourEpisode.E3, 0.9, Map.of(), 21);

    assertEquals(SupportState.B2, policy.supportState(LastHourEpisode.E3, 21));
    assertTrue(policy.nextDecision(LastHourEpisode.E3, 21, true).isEmpty());
    assertEquals(Action.HINT_OFFER, policy.lastAction(LastHourEpisode.E3));
  }

  @Test
  void risksFollowTheCurrentJudgmentAndResetOnProgress() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID actor = UUID.randomUUID();
    UUID observer = UUID.randomUUID();
    policy.started(LastHourPuzzle.LOGIN, 0);
    policy.registerParticipant(LastHourEpisode.E2, observer);
    wrongAttempt(policy, LastHourPuzzle.LOGIN, actor, "wrong", 1);

    assertEquals(
        Map.of(observer, FrustrationRisk.NONE, actor, FrustrationRisk.NONE),
        policy.risks(LastHourEpisode.E2));

    judge(
        policy,
        LastHourEpisode.E2,
        0.2,
        Map.of(actor, FrustrationRisk.HIGH, observer, FrustrationRisk.LOW),
        5);
    assertEquals(FrustrationRisk.HIGH, policy.risks(LastHourEpisode.E2).get(actor));
    assertEquals(FrustrationRisk.LOW, policy.risks(LastHourEpisode.E2).get(observer));

    wrongAttempt(policy, LastHourPuzzle.LOGIN, actor, "again", 6);
    assertEquals(FrustrationRisk.HIGH, policy.risks(LastHourEpisode.E2).get(actor));

    policy.solved(LastHourPuzzle.LOGIN, 7);
    assertEquals(FrustrationRisk.LOW, policy.risks(LastHourEpisode.E2).get(actor));
  }

  @Test
  void adaptiveHintIdsAreStablePerEpisode() {
    assertEquals("adaptive-hint-e1", LastHourAdaptation.adaptiveHintId(LastHourEpisode.E1));
    assertEquals("adaptive-hint-e6", LastHourAdaptation.adaptiveHintId(LastHourEpisode.E6));
  }

  @Test
  void decisionIsNotConsumedWithoutRecipients() {
    LastHourAdaptationPolicy policy = new LastHourAdaptationPolicy(THRESHOLDS);
    UUID participant = UUID.randomUUID();
    policy.started(LastHourPuzzle.POWER, 0);
    policy.action(LastHourPuzzle.POWER, "pc", "power-button", participant, 1);
    judge(policy, LastHourEpisode.E1, 0.9, Map.of(), 10);

    assertTrue(policy.nextDecision(LastHourEpisode.E1, 10, false).isEmpty());
    assertEquals(Action.NONE, policy.lastAction(LastHourEpisode.E1));
    assertEquals(
        Action.HINT_OFFER,
        policy.nextDecision(LastHourEpisode.E1, 10, true).orElseThrow().action());
  }

  @Test
  void failedRecordingSyncIsRetriedUntilItIsPersisted() {
    UUID participant = UUID.randomUUID();
    var results = new HashSet<UUID>();
    AtomicInteger attempts = new AtomicInteger();

    assertFalse(
        LastHourAdaptation.recordingSyncOnce(
            results,
            participant,
            ignored -> {
              attempts.incrementAndGet();
              return false;
            }));
    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            results,
            participant,
            ignored -> {
              attempts.incrementAndGet();
              return true;
            }));

    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            results,
            participant,
            ignored -> {
              attempts.incrementAndGet();
              return true;
            }));

    assertEquals(2, attempts.get());
    assertTrue(results.contains(participant));
  }

  @Test
  void successfulRecordingSyncIsEmittedExactlyOnce() {
    UUID participant = UUID.randomUUID();
    var results = new HashSet<UUID>();
    AtomicInteger attempts = new AtomicInteger();

    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            results,
            participant,
            ignored -> {
              attempts.incrementAndGet();
              return true;
            }));
    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            results,
            participant,
            ignored -> {
              attempts.incrementAndGet();
              return true;
            }));

    assertEquals(1, attempts.get());
    assertTrue(results.contains(participant));
  }

  @Test
  void visibleRecordingSyncRetriesAndThenUsesTheLatestPersistedRecordingIdentity() {
    UUID participant = UUID.randomUUID();
    UUID recording = UUID.randomUUID();
    var recordingIds = new HashMap<UUID, UUID>();
    AtomicInteger attempts = new AtomicInteger();

    assertFalse(
        LastHourAdaptation.recordingSyncVisibleOnce(
            recordingIds, participant, recording, ignored -> attempts.incrementAndGet() > 1));
    assertTrue(
        LastHourAdaptation.recordingSyncVisibleOnce(
            recordingIds, participant, recording, ignored -> attempts.incrementAndGet() > 1));
    assertTrue(
        LastHourAdaptation.recordingSyncVisibleOnce(
            recordingIds, participant, recording, ignored -> attempts.incrementAndGet() > 1));
    UUID restartedRecording = UUID.randomUUID();
    assertTrue(
        LastHourAdaptation.recordingSyncVisibleOnce(
            recordingIds,
            participant,
            restartedRecording,
            ignored -> attempts.incrementAndGet() > 1));

    assertEquals(3, attempts.get());
    assertEquals(restartedRecording, recordingIds.get(participant));
  }

  @Test
  void restartedConnectionGetsANewMarkerWithoutResettingTheOtherParticipant() {
    UUID participant = UUID.randomUUID();
    UUID partner = UUID.randomUUID();
    UUID oldRecording = UUID.randomUUID();
    UUID newRecording = UUID.randomUUID();
    UUID partnerRecording = UUID.randomUUID();
    Session first = new Session(null, null, null);
    Session restarted = new Session(null, null, null);
    var connections = new HashMap<UUID, Session>();
    var synced = new HashSet<UUID>();
    var visible = new HashMap<UUID, UUID>();
    AtomicInteger markers = new AtomicInteger();

    LastHourAdaptation.beginRecordingConnection(connections, synced, visible, participant, first);
    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            synced,
            participant,
            ignored -> {
              markers.incrementAndGet();
              return true;
            }));
    assertTrue(
        LastHourAdaptation.recordingSyncVisibleOnce(
            visible, participant, oldRecording, ignored -> true));
    synced.add(partner);
    visible.put(partner, partnerRecording);

    // Repeated ticks and world snapshots on one connection keep the original recording marker.
    LastHourAdaptation.beginRecordingConnection(connections, synced, visible, participant, first);
    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            synced,
            participant,
            ignored -> {
              markers.incrementAndGet();
              return true;
            }));
    assertEquals(oldRecording, visible.get(participant));
    assertEquals(1, markers.get());

    LastHourAdaptation.beginRecordingConnection(
        connections, synced, visible, participant, restarted);
    assertFalse(synced.contains(participant));
    assertFalse(visible.containsKey(participant));
    assertTrue(synced.contains(partner));
    assertEquals(partnerRecording, visible.get(partner));
    assertTrue(
        LastHourAdaptation.recordingSyncOnce(
            synced,
            participant,
            ignored -> {
              markers.incrementAndGet();
              return true;
            }));
    assertTrue(
        LastHourAdaptation.recordingSyncVisibleOnce(
            visible, participant, newRecording, ignored -> true));
    assertEquals(newRecording, visible.get(participant));
    assertEquals(2, markers.get());
  }

  @Test
  void firstTeamHintResponseSettlesTheOffer() {
    var settled = EnumSet.noneOf(LastHourEpisode.class);

    assertTrue(LastHourAdaptation.settleOnce(settled, LastHourEpisode.E3));
    assertFalse(LastHourAdaptation.settleOnce(settled, LastHourEpisode.E3));
  }

  @Test
  void hintResponseAlwaysHasOneUnambiguousTerminalOutcome() {
    assertEquals(
        new LastHourAdaptation.HintResponse(false, "team-rejected-hint"),
        LastHourAdaptation.resolveHintResponse(false, true, true));
    assertEquals(
        new LastHourAdaptation.HintResponse(false, "no-eligible-recipients-at-response"),
        LastHourAdaptation.resolveHintResponse(true, false, false));
    assertEquals(
        new LastHourAdaptation.HintResponse(false, "episode-completed-before-response"),
        LastHourAdaptation.resolveHintResponse(true, true, false));
    assertEquals(
        new LastHourAdaptation.HintResponse(true, "team-opened-hint"),
        LastHourAdaptation.resolveHintResponse(true, true, true));
  }

  private static void judge(
      LastHourAdaptationPolicy policy,
      LastHourEpisode episode,
      double stuckProbability,
      Map<UUID, FrustrationRisk> risks,
      long nowSeconds) {
    var observation = policy.observation(episode, nowSeconds).orElseThrow();
    assertTrue(policy.judged(observation, new Judgment(stuckProbability, risks), nowSeconds));
  }

  private static void wrongAttempt(
      LastHourAdaptationPolicy policy, UUID participant, String answer, long nowSeconds) {
    wrongAttempt(policy, LastHourPuzzle.STORAGE_ACCESS, participant, answer, nowSeconds);
  }

  private static void wrongAttempt(
      LastHourAdaptationPolicy policy,
      LastHourPuzzle puzzle,
      UUID participant,
      String answer,
      long nowSeconds) {
    policy.attempt(puzzle, "storage-keypad", answer, false, participant, nowSeconds);
  }
}
