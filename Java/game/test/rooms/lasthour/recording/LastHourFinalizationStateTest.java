package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.network.messages.c2s.RecordingFinalizationResult;
import engine.network.messages.s2c.RecordingFinalizationComplete.RunStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rooms.lasthour.recording.LastHourFinalizationState.Acceptance;
import rooms.lasthour.recording.LastHourFinalizationState.ExpectedClient;

/** Tests the server-side recording-finalization state machine without a network. */
class LastHourFinalizationStateTest {
  @TempDir Path temporaryDirectory;
  private static final UUID COMPLETION_ID = UUID.fromString("fa20b932-dac1-422b-aa43-fabc74631925");
  private static final Instant STARTED_AT = Instant.parse("2026-09-04T18:00:00Z");
  private static final List<ExpectedClient> CLIENTS =
      List.of(
          new ExpectedClient(
              (short) 2, "930f93b3-20fb-45a3-ac69-c056764422ae", recordingId((short) 2)),
          new ExpectedClient(
              (short) 1, "f1e82a0f-f42c-4fe1-a02e-c55e4f0c7055", recordingId((short) 1)));

  @Test
  void twoUsableMappedResultsCompleteTheRun() {
    LastHourFinalizationState state = completedState();

    acceptAndPersist(state, (short) 1, result((short) 1));
    acceptAndPersist(state, (short) 2, result((short) 2));
    state.finish(false, STARTED_AT.plusSeconds(3));

    assertTrue(state.allResultsReceived());
    assertEquals(RunStatus.COMPLETE, state.runStatus());
    assertEquals(List.of(), state.missingClientIds());
  }

  @Test
  void duplicateUnknownAndStaleResultsDoNotAdvanceTheRun() {
    LastHourFinalizationState state = completedState();

    assertEquals(Acceptance.ACCEPTED, state.accept((short) 1, result((short) 1)));
    assertEquals(Acceptance.DUPLICATE, state.accept((short) 1, result((short) 1)));
    assertEquals(Acceptance.UNKNOWN_CLIENT, state.accept((short) 3, result((short) 3)));
    assertEquals(
        Acceptance.WRONG_COMPLETION, state.accept((short) 2, result(UUID.randomUUID(), (short) 2)));

    assertFalse(state.allResultsReceived());
    assertEquals(List.of((short) 2), state.missingClientIds());
  }

  @Test
  void timeoutOrUnusablePartnerStillAllowsTheOtherParticipantsReplay() {
    LastHourFinalizationState timeout = completedState();
    acceptAndPersist(timeout, (short) 1, result((short) 1));
    timeout.finish(true, STARTED_AT.plusSeconds(90));
    assertEquals(RunStatus.COMPLETE, timeout.runStatus());

    LastHourFinalizationState dropped = completedState();
    acceptAndPersist(dropped, (short) 1, result((short) 1));
    acceptAndPersist(dropped, (short) 2, resultWithDrops((short) 2));
    dropped.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.COMPLETE, dropped.runStatus());
  }

  @Test
  void operatorStopRemainsAbortedButKeepsValidRecordingsForReplay() {
    LastHourFinalizationState state =
        new LastHourFinalizationState(
            COMPLETION_ID, false, "operator", 2, 90, STARTED_AT, 0L, CLIENTS);
    acceptAndPersist(state, (short) 1, result((short) 1));
    acceptAndPersist(state, (short) 2, resultWithDrops((short) 2));
    state.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.ABORTED, state.runStatus());
    assertTrue(state.recordingsValid());

    LastHourFinalizationState usable =
        new LastHourFinalizationState(
            COMPLETION_ID, false, "operator", 2, 90, STARTED_AT, 0L, CLIENTS);
    acceptAndPersist(usable, (short) 1, result((short) 1));
    acceptAndPersist(usable, (short) 2, result((short) 2));
    usable.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.ABORTED, usable.runStatus());
    assertTrue(usable.recordingsValid());
  }

  @Test
  void missingPartnerMappingOrDisconnectedPartnerDoesNotInvalidateUsableRecording() {
    LastHourFinalizationState unmapped =
        new LastHourFinalizationState(
            COMPLETION_ID,
            true,
            "done",
            2,
            90,
            STARTED_AT,
            0L,
            List.of(CLIENTS.getLast(), new ExpectedClient((short) 2, "", "")));
    acceptAndPersist(unmapped, (short) 1, result((short) 1));
    acceptAndPersist(unmapped, (short) 2, result((short) 2));
    unmapped.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.COMPLETE, unmapped.runStatus());

    LastHourFinalizationState onlyOneClient =
        new LastHourFinalizationState(
            COMPLETION_ID, true, "done", 2, 90, STARTED_AT, 0L, List.of(CLIENTS.getLast()));
    acceptAndPersist(onlyOneClient, (short) 1, result((short) 1));
    onlyOneClient.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.COMPLETE, onlyOneClient.runStatus());
  }

  @Test
  void missingOrMismatchedVisibleSyncExcludesOnlyThatParticipant() {
    LastHourFinalizationState missingSync =
        new LastHourFinalizationState(
            COMPLETION_ID,
            true,
            "done",
            2,
            90,
            STARTED_AT,
            0L,
            List.of(
                CLIENTS.getFirst(),
                new ExpectedClient((short) 1, CLIENTS.getLast().participantId(), "")));
    acceptAndPersist(missingSync, (short) 1, result((short) 1));
    acceptAndPersist(missingSync, (short) 2, result((short) 2));
    missingSync.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.COMPLETE, missingSync.runStatus());

    LastHourFinalizationState mismatchedSync = completedState();
    acceptAndPersist(
        mismatchedSync,
        (short) 1,
        resultWithIdentifiers((short) 1, "P001", UUID.randomUUID().toString()));
    acceptAndPersist(mismatchedSync, (short) 2, result((short) 2));
    mismatchedSync.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.COMPLETE, mismatchedSync.runStatus());
  }

  @Test
  void duplicateStudyRecordingOrParticipantIdentitiesAreInvalid() {
    LastHourFinalizationState duplicateStudy = completedState();
    acceptAndPersist(
        duplicateStudy,
        (short) 1,
        resultWithIdentifiers((short) 1, "P001", recordingId((short) 1)));
    acceptAndPersist(
        duplicateStudy,
        (short) 2,
        resultWithIdentifiers((short) 2, " p001 ", recordingId((short) 2)));
    duplicateStudy.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.INVALID, duplicateStudy.runStatus());

    LastHourFinalizationState duplicateRecording =
        new LastHourFinalizationState(
            COMPLETION_ID,
            true,
            "done",
            2,
            90,
            STARTED_AT,
            0L,
            List.of(
                new ExpectedClient(
                    (short) 1, CLIENTS.getLast().participantId(), recordingId((short) 1)),
                new ExpectedClient(
                    (short) 2, CLIENTS.getFirst().participantId(), recordingId((short) 1))));
    acceptAndPersist(duplicateRecording, (short) 1, result((short) 1));
    acceptAndPersist(
        duplicateRecording,
        (short) 2,
        resultWithIdentifiers((short) 2, "P002", recordingId((short) 1)));
    duplicateRecording.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.INVALID, duplicateRecording.runStatus());

    LastHourFinalizationState duplicateParticipant =
        new LastHourFinalizationState(
            COMPLETION_ID,
            true,
            "done",
            2,
            90,
            STARTED_AT,
            0L,
            List.of(
                CLIENTS.getLast(),
                new ExpectedClient(
                    (short) 2, CLIENTS.getLast().participantId(), recordingId((short) 2))));
    acceptAndPersist(duplicateParticipant, (short) 1, result((short) 1));
    acceptAndPersist(duplicateParticipant, (short) 2, result((short) 2));
    duplicateParticipant.finish(false, STARTED_AT.plusSeconds(3));
    assertEquals(RunStatus.INVALID, duplicateParticipant.runStatus());
  }

  @Test
  void missingFinalizationTrackingEventExcludesOnlyThatParticipant() {
    LastHourFinalizationState state = completedState();
    assertEquals(Acceptance.ACCEPTED, state.accept((short) 1, result((short) 1)));
    assertEquals(Acceptance.ACCEPTED, state.accept((short) 2, result((short) 2)));
    state.markFinalizationEventPersisted((short) 1);
    state.finish(false, STARTED_AT.plusSeconds(3));

    assertEquals(List.of((short) 2), state.unpersistedResultClientIds());
    assertEquals(RunStatus.COMPLETE, state.runStatus());

    state.markFinalizationEventPersisted((short) 2);
    assertEquals(RunStatus.COMPLETE, state.runStatus());
  }

  @Test
  void unusablePartnersDuplicateStudyIdDoesNotBlockTheUsableParticipant() {
    LastHourFinalizationState state = completedState();
    acceptAndPersist(state, (short) 1, result((short) 1));
    acceptAndPersist(
        state, (short) 2, resultWithIdentifiers(COMPLETION_ID, 4L, "P001", recordingId((short) 2)));
    state.finish(false, STARTED_AT.plusSeconds(3));

    assertEquals(RunStatus.COMPLETE, state.runStatus());
    assertTrue(state.recordingUsable((short) 1));
    assertFalse(state.recordingUsable((short) 2));
  }

  @Test
  void noUsableRecordingRemainsInvalid() {
    LastHourFinalizationState state = completedState();
    acceptAndPersist(state, (short) 1, resultWithDrops((short) 1));
    state.finish(true, STARTED_AT.plusSeconds(90));

    assertEquals(RunStatus.INVALID, state.runStatus());
    assertFalse(state.recordingsValid());
  }

  @Test
  void writesOneReadableSummaryNextToTheTrackingOutbox() throws Exception {
    LastHourFinalizationState state = completedState();
    acceptAndPersist(state, (short) 1, result((short) 1));
    acceptAndPersist(state, (short) 2, result((short) 2));
    state.finish(false, STARTED_AT.plusSeconds(3));
    Path tracking = temporaryDirectory.resolve("session.jsonl");

    Path summary = LastHourRunFinalizer.writeSummary(state, RunStatus.COMPLETE, tracking);
    String json = Files.readString(summary);

    assertEquals(temporaryDirectory.resolve("session.pilot-run-summary.json"), summary);
    assertTrue(json.contains("\"schemaVersion\" : 2"));
    assertTrue(json.contains("\"runStatus\" : \"COMPLETE\""));
    assertTrue(json.contains("\"recordingResultsReceived\" : 2"));
    assertTrue(json.contains("\"usableForReplay\" : true"));
    assertTrue(json.contains("\"visibleSyncMatched\" : true"));
    assertTrue(json.contains("\"finalizationEventPersisted\" : true"));
  }

  private static LastHourFinalizationState completedState() {
    return new LastHourFinalizationState(
        COMPLETION_ID, true, "done", 2, 90, STARTED_AT, 0L, CLIENTS);
  }

  private static void acceptAndPersist(
      LastHourFinalizationState state, short clientId, RecordingFinalizationResult result) {
    assertEquals(Acceptance.ACCEPTED, state.accept(clientId, result));
    state.markFinalizationEventPersisted(clientId);
  }

  private static RecordingFinalizationResult result(short clientId) {
    return result(COMPLETION_ID, clientId);
  }

  private static RecordingFinalizationResult resultWithDrops(short clientId) {
    return result(COMPLETION_ID, clientId, 4L);
  }

  private static RecordingFinalizationResult result(UUID completionId, short clientId) {
    return result(completionId, clientId, 0L);
  }

  private static RecordingFinalizationResult result(
      UUID completionId, short clientId, long dropped) {
    return resultWithIdentifiers(completionId, dropped, "P00" + clientId, recordingId(clientId));
  }

  private static RecordingFinalizationResult resultWithIdentifiers(
      short clientId, String studyId, String recordingId) {
    return resultWithIdentifiers(COMPLETION_ID, 0L, studyId, recordingId);
  }

  private static RecordingFinalizationResult resultWithIdentifiers(
      UUID completionId, long dropped, String studyId, String recordingId) {
    return new RecordingFinalizationResult(
        completionId,
        RecordingFinalizationResult.Status.COMPLETE,
        studyId,
        recordingId,
        20,
        1280,
        720,
        LastHourRecordingProtocol.FORMAT,
        20_000L,
        300L,
        2L,
        dropped,
        1,
        "windows-x86_64",
        "bundled",
        "",
        false);
  }

  private static String recordingId(short clientId) {
    return UUID.nameUUIDFromBytes(new byte[] {(byte) clientId}).toString();
  }
}
