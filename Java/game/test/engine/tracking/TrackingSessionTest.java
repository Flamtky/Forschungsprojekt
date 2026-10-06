package engine.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tracking.core.TrackingEvent;
import tracking.core.TrackingEventType;
import tracking.core.TrackingJson;

class TrackingSessionTest {
  @TempDir Path outboxDirectory;

  @Test
  void roomEventsUseTheSessionSequenceAndJsonlOutbox() throws Exception {
    TrackingSession session = session();
    TrackingEvent teamEvent =
        session.roomEvent("puzzle", "shared-object", TrackingJson.object().put("state", "opened"));
    UUID participantId = session.participantJoined((short) 1, false).orElseThrow();
    TrackingEvent participantEvent =
        session.roomEvent(
            "puzzle",
            "personal-object",
            TrackingJson.object().put("state", "inspected"),
            participantId);

    var persisted = TrackingJson.readJsonlRecoveringTruncatedTail(session.outboxPath());

    assertEquals(TrackingEventType.ROOM_EVENT, teamEvent.eventType());
    assertTrue(teamEvent.participantId().isEmpty());
    assertEquals(Optional.of(participantId), participantEvent.participantId());
    assertEquals(teamEvent.sessionSequence() + 2, participantEvent.sessionSequence());
    assertEquals(
        java.util.List.of(teamEvent, participantEvent),
        persisted.events().stream()
            .filter(event -> event.eventType() == TrackingEventType.ROOM_EVENT)
            .toList());
  }

  @Test
  void alternativeCompletionClosesPuzzleWithoutSolvedEvent() throws Exception {
    TrackingSession session = session();
    session.puzzleStarted("storage-access");

    session.completePuzzleWithoutSolvedEvent("storage-access");
    session.puzzleStarted("blue-usb");
    session.puzzleSolved("blue-usb");

    var persisted = TrackingJson.readJsonlRecoveringTruncatedTail(session.outboxPath());
    assertTrue(session.currentPuzzleId().isEmpty());
    assertTrue(session.puzzleStarted("storage-access").isEmpty());
    assertEquals(
        java.util.List.of("blue-usb"),
        persisted.events().stream()
            .filter(event -> event.eventType() == TrackingEventType.PUZZLE_SOLVED)
            .map(event -> event.puzzleId().orElseThrow())
            .toList());
  }

  private TrackingSession session() {
    return new TrackingSession(
        new TrackingConfig(
            "room",
            URI.create("http://127.0.0.1:8088"),
            Optional.empty(),
            outboxDirectory,
            TrackingConfig.DEFAULT_OPERATOR_EMAIL));
  }

  @Test
  void disconnectedIdentityRemainsAvailableForFinalizationAndReconnect() {
    TrackingSession session = session();
    UUID participant = session.participantJoined((short) 1, false).orElseThrow();
    session.participantLeft((short) 1);
    assertTrue(session.participantForClient((short) 1).isEmpty());
    assertEquals(Optional.of(participant), session.retainedParticipantForClient((short) 1));
    assertEquals(Optional.of(participant), session.participantJoined((short) 1, false));
  }
}
