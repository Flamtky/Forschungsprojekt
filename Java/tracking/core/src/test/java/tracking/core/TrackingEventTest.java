package tracking.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class TrackingEventTest {
  private static final UUID SESSION_ID = UUID.randomUUID();

  @Test
  void roomEventAcceptsTeamAndParticipantAttribution() {
    assertDoesNotThrow(() -> roomEvent(Optional.empty(), Optional.empty()));
    assertDoesNotThrow(() -> roomEvent(Optional.of(UUID.randomUUID()), Optional.empty()));
  }

  @Test
  void roomEventRequiresPuzzleAndObjectWithoutOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () -> event(Optional.empty(), Optional.empty(), Optional.of("object"), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> event(Optional.empty(), Optional.of("puzzle"), Optional.empty(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            event(
                Optional.empty(),
                Optional.of("puzzle"),
                Optional.of("object"),
                Optional.of(TrackingOutcome.CORRECT)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            event(
                Optional.empty(),
                Optional.of("puzzle"),
                Optional.of("object"),
                Optional.empty(),
                JsonNodeFactory.instance.arrayNode()));
  }

  private static TrackingEvent roomEvent(
      Optional<UUID> participantId, Optional<TrackingOutcome> outcome) {
    return event(participantId, Optional.of("puzzle"), Optional.of("object"), outcome);
  }

  private static TrackingEvent event(
      Optional<UUID> participantId,
      Optional<String> puzzleId,
      Optional<String> objectId,
      Optional<TrackingOutcome> outcome) {
    return event(
        participantId, puzzleId, objectId, outcome, TrackingJson.object().put("state", "changed"));
  }

  private static TrackingEvent event(
      Optional<UUID> participantId,
      Optional<String> puzzleId,
      Optional<String> objectId,
      Optional<TrackingOutcome> outcome,
      JsonNode payload) {
    return new TrackingEvent(
        1,
        SESSION_ID,
        1,
        participantId,
        "room",
        TrackingEventType.ROOM_EVENT,
        puzzleId,
        objectId,
        outcome,
        0,
        Instant.EPOCH,
        payload);
  }
}
