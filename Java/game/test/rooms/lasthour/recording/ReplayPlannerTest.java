package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tracking.core.TrackingEvent;
import tracking.core.TrackingEventType;
import tracking.core.TrackingJson;

/** Selection uses each client's own marker, stable LOW phases and no terminal risk resets. */
class ReplayPlannerTest {
  @Test
  void restartedRecordingUsesItsOwnSyncAndOnlyMomentsInsideItsDuration() {
    UUID session = UUID.randomUUID(), participant = UUID.randomUUID();
    String oldRecording = UUID.randomUUID().toString();
    String recording = UUID.randomUUID().toString();
    List<TrackingEvent> events = new ArrayList<>();
    add(events, session, participant, 100, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        participant,
        200,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + oldRecording + "\",\"videoSyncMs\":500}");
    add(
        events,
        session,
        participant,
        30000,
        "adaptation.risk",
        "{\"episode\":\"E1\",\"risk\":\"MEDIUM\"}");
    add(
        events,
        session,
        null,
        50000,
        "adaptation.decision",
        "{\"episode\":\"E1\",\"action\":\"HINT_OFFER\"}");
    add(
        events,
        session,
        null,
        50001,
        "adaptation.intervention",
        "{\"episode\":\"E1\",\"action\":\"HINT_OFFER\"}");
    add(events, session, participant, 100000, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        participant,
        100100,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + recording + "\",\"videoSyncMs\":500}");
    add(
        events,
        session,
        participant,
        110000,
        "adaptation.risk",
        "{\"episode\":\"E2\",\"risk\":\"MEDIUM\"}");
    add(
        events,
        session,
        null,
        140000,
        "adaptation.decision",
        "{\"episode\":\"E2\",\"action\":\"HINT_OFFER\"}");
    add(
        events,
        session,
        null,
        140001,
        "adaptation.intervention",
        "{\"episode\":\"E2\",\"action\":\"HINT_OFFER\"}");
    add(
        events,
        session,
        participant,
        250000,
        "adaptation.risk",
        "{\"episode\":\"E3\",\"risk\":\"HIGH\"}");

    var plan = ReplayPlanner.create(events, participant, "P001", recording, 100000);

    assertEquals(100000, plan.trackingSyncMs());
    assertEquals(500, plan.videoSyncMs());
    assertEquals(List.of(140000L), plan.clips().stream().map(c -> c.eventMs()).toList());
    assertTrue(plan.clips().stream().allMatch(c -> c.startMs() >= 0 && c.endMs() <= 100000));
    assertEquals(40500, plan.clips().getFirst().splitMs());
    assertThrows(
        IllegalArgumentException.class,
        () -> ReplayPlanner.create(events, participant, "P001", oldRecording, 100000));
  }

  @Test
  void separateClientOffsetsMediumRisksAndTwoStageIntervention() {
    UUID session = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    String recordingA = UUID.randomUUID().toString(), recordingB = UUID.randomUUID().toString();
    List<TrackingEvent> events = new ArrayList<>();
    add(events, session, a, 100, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        a,
        200,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + recordingA + "\",\"videoSyncMs\":500}");
    add(events, session, b, 300, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        b,
        400,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + recordingB + "\",\"videoSyncMs\":900}");
    add(events, session, a, 30000, "adaptation.risk", "{\"episode\":\"E1\",\"risk\":\"MEDIUM\"}");
    add(events, session, b, 30000, "adaptation.risk", "{\"episode\":\"E1\",\"risk\":\"HIGH\"}");
    add(
        events,
        session,
        a,
        40000,
        "adaptation.risk",
        "{\"episode\":\"E1\",\"risk\":\"LOW\",\"reason\":\"episode-completed\"}");
    add(
        events,
        session,
        null,
        100000,
        "adaptation.decision",
        "{\"episode\":\"E3\",\"action\":\"HINT_OFFER\"}");
    add(
        events,
        session,
        null,
        100001,
        "adaptation.intervention",
        "{\"episode\":\"E3\",\"action\":\"HINT_OFFER\"}");
    var planA = ReplayPlanner.create(events, a, "A", recordingA, 200000);
    var planB = ReplayPlanner.create(events, b, "B", recordingB, 200000);
    assertEquals(2, planA.clips().size());
    assertEquals(2, planB.clips().size());
    assertEquals("MEDIUM", planA.clips().getFirst().risk());
    assertEquals("HIGH", planB.clips().getFirst().risk());
    assertEquals(5400, planA.clips().getFirst().startMs());
    assertEquals(5600, planB.clips().getFirst().startMs());
    assertEquals(100400, planA.clips().getLast().splitMs());
    assertTrue(planA.clips().getLast().intervention());
    assertEquals(planA, ReplayPlanner.create(events, a, "A", recordingA, 200000));
  }

  @Test
  void lowBaselinesSitInsideStableLowPhasesAndInterventionsKeepTheParticipantsRisk() {
    UUID session = UUID.randomUUID(), a = UUID.randomUUID();
    String recording = UUID.randomUUID().toString();
    List<TrackingEvent> events = new ArrayList<>();
    add(events, session, a, 100, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        a,
        200,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + recording + "\",\"videoSyncMs\":500}");
    // LOW phases: [10 s, 45 s] ends one pre-roll before the rise; [90 s, 250 s] is split by the
    // decision window [120 s, 170 s]; [260 s, recording end] is the longest.
    add(events, session, a, 10000, "adaptation.risk", "{\"episode\":\"E1\",\"risk\":\"LOW\"}");
    add(events, session, a, 70000, "adaptation.risk", "{\"episode\":\"E1\",\"risk\":\"MEDIUM\"}");
    add(events, session, a, 90000, "adaptation.risk", "{\"episode\":\"E1\",\"risk\":\"LOW\"}");
    add(
        events,
        session,
        null,
        150000,
        "adaptation.decision",
        "{\"episode\":\"E1\",\"action\":\"HINT_OFFER\"}");
    add(
        events,
        session,
        null,
        150001,
        "adaptation.intervention",
        "{\"episode\":\"E1\",\"action\":\"HINT_OFFER\"}");
    add(
        events,
        session,
        a,
        250000,
        "adaptation.risk",
        "{\"episode\":\"E1\",\"risk\":\"LOW\",\"reason\":\"episode-completed\"}");
    add(events, session, a, 260000, "adaptation.risk", "{\"episode\":\"E2\",\"risk\":\"LOW\"}");

    var clips = ReplayPlanner.create(events, a, "A", recording, 400000).clips();

    // The second LOW clip comes from the other half, not from the longer [170 s, 250 s] piece.
    assertEquals(
        List.of("LOW", "MEDIUM", "LOW", "LOW"), clips.stream().map(c -> c.risk()).toList());
    assertEquals(12900, clips.getFirst().startMs());
    assertEquals(42900, clips.getFirst().splitMs());
    assertEquals(45400, clips.get(1).startMs());
    assertEquals("HINT_OFFER", clips.get(2).action());
    assertEquals("E2", clips.getLast().episode());
    assertEquals(314700, clips.getLast().startMs());
  }

  @Test
  void unjudgedRiskEndsALowPhase() {
    UUID session = UUID.randomUUID(), a = UUID.randomUUID();
    String recording = UUID.randomUUID().toString();
    List<TrackingEvent> events = new ArrayList<>();
    add(events, session, a, 100, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        a,
        200,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + recording + "\",\"videoSyncMs\":500}");
    add(events, session, a, 10000, "adaptation.risk", "{\"episode\":\"E1\",\"risk\":\"LOW\"}");
    add(
        events,
        session,
        a,
        60000,
        "adaptation.risk",
        "{\"episode\":\"E1\",\"risk\":\"NONE\",\"reason\":\"no-current-judgment\"}");

    var clips = ReplayPlanner.create(events, a, "A", recording, 400000).clips();

    assertEquals(List.of("LOW"), clips.stream().map(c -> c.risk()).toList());
    assertTrue(clips.getFirst().splitMs() <= 60000 + 400);
  }

  private static void add(
      List<TrackingEvent> events,
      UUID session,
      UUID participant,
      long time,
      String object,
      String payload) {
    events.add(
        new TrackingEvent(
            1,
            session,
            events.size() + 1,
            Optional.ofNullable(participant),
            "the-last-hour",
            TrackingEventType.ROOM_EVENT,
            Optional.of("power"),
            Optional.of(object),
            Optional.empty(),
            time,
            Instant.EPOCH.plusMillis(time),
            TrackingJson.object(payload)));
  }
}
