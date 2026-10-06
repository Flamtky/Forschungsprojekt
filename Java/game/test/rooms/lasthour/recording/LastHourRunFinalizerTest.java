package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import engine.network.messages.c2s.RecordingFinalizationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rooms.lasthour.recording.LastHourFinalizationState.ExpectedClient;
import tracking.core.ReplayProtocol;
import tracking.core.TrackingEvent;
import tracking.core.TrackingEventType;
import tracking.core.TrackingJson;

/**
 * Exercises per-participant planning, signing and approval persistence against real outbox data.
 */
class LastHourRunFinalizerTest {
  @TempDir Path directory;

  @Test
  void missingPartnerDoesNotPreventSignedReplayAndPersistedApproval() throws Exception {
    verifyReplayWithPartner(false);
  }

  @Test
  void partnerWithoutReplayMomentsDoesNotPreventOtherSignedReplay() throws Exception {
    verifyReplayWithPartner(true);
  }

  private void verifyReplayWithPartner(boolean partnerResult) throws Exception {
    UUID session = UUID.randomUUID();
    UUID participant = UUID.randomUUID();
    UUID partner = UUID.randomUUID();
    UUID recording = UUID.randomUUID();
    UUID partnerRecording = UUID.randomUUID();
    UUID completion = UUID.randomUUID();
    var state =
        new LastHourFinalizationState(
            completion,
            true,
            "done",
            2,
            90,
            Instant.EPOCH.plusSeconds(60),
            0,
            List.of(
                new ExpectedClient((short) 1, participant.toString(), recording.toString()),
                new ExpectedClient((short) 2, partner.toString(), partnerRecording.toString())));
    state.accept((short) 1, result(completion, recording, "P001"));
    state.markFinalizationEventPersisted((short) 1);
    if (partnerResult) {
      state.accept((short) 2, result(completion, partnerRecording, "P002"));
      state.markFinalizationEventPersisted((short) 2);
    }
    state.finish(!partnerResult, Instant.EPOCH.plusSeconds(90));
    List<TrackingEvent> events = new ArrayList<>();
    add(events, session, participant, 100, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        participant,
        200,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + recording + "\",\"videoSyncMs\":500}");
    add(events, session, partner, 300, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        partner,
        400,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + partnerRecording + "\",\"videoSyncMs\":500}");
    add(
        events,
        session,
        participant,
        30000,
        "adaptation.risk",
        "{\"episode\":\"E1\",\"risk\":\"MEDIUM\"}");
    // A restarted client confirms a new empty recording after the frozen end-of-game snapshot.
    add(events, session, participant, 61000, "pilot.recording_sync", "{}");
    add(
        events,
        session,
        participant,
        62000,
        "pilot.recording_sync_visible",
        "{\"recordingId\":\"" + UUID.randomUUID() + "\",\"videoSyncMs\":500}");
    Path outbox = directory.resolve("session.jsonl");
    List<String> lines = new ArrayList<>();
    lines.add(
        TrackingJson.writeJsonlRecord(
            new tracking.core.TrackingJsonlRecord.Session(
                new tracking.core.TrackingSessionDescriptor(
                    1, session, "the-last-hour", Instant.EPOCH))));
    events.forEach(
        event ->
            lines.add(
                TrackingJson.writeJsonlRecord(new tracking.core.TrackingJsonlRecord.Event(event))));
    Files.write(outbox, lines);
    var errors = new HashMap<Short, String>();
    String testKey = "test-only-replay-signing-key-32-characters";

    var launches =
        LastHourRunFinalizer.prepareReplays(
            state,
            outbox,
            (plan, deletionHash) ->
                new ReplayProtocol.Launch(
                    "http://localhost:8080", ReplayProtocol.sign(plan, testKey), deletionHash),
            errors);

    assertEquals(java.util.Set.of((short) 1), launches.keySet());
    var launch =
        ReplayProtocol.JSON.readValue(launches.get((short) 1), ReplayProtocol.Launch.class);
    var plan = ReplayProtocol.verify(launch.ticket(), testKey);
    assertEquals(recording.toString(), plan.recordingId());
    assertEquals(participant.toString(), plan.participantId());
    assertEquals(1, plan.clips().size());
    assertEquals(partnerResult, errors.containsKey((short) 2));
    assertEquals("READY", LastHourRunFinalizer.replayStatus(launches, !errors.isEmpty()));
    var approvals =
        ReplayProtocol.JSON.readTree(
            Files.readString(directory.resolve("session.jsonl.replay-approvals.json")));
    assertEquals(1, approvals.size());
    assertEquals(recording.toString(), approvals.get(0).get("recordingId").stringValue());
    assertEquals(
        launch.deletionHash(),
        ReplayProtocol.hash(approvals.get(0).get("deletionCode").stringValue()));
  }

  private static RecordingFinalizationResult result(UUID completion, UUID recording, String study) {
    return new RecordingFinalizationResult(
        completion,
        RecordingFinalizationResult.Status.COMPLETE,
        study,
        recording.toString(),
        20,
        1280,
        720,
        LastHourRecordingProtocol.FORMAT,
        20000,
        2000,
        0,
        0,
        1,
        "windows-x86_64",
        "bundled",
        "",
        false);
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
            Optional.of(participant),
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
