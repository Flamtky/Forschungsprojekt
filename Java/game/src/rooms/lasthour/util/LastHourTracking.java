package rooms.lasthour.util;

import engine.Entity;
import engine.Game;
import engine.network.handler.NettyNetworkHandler;
import engine.network.messages.c2s.DialogResponseMessage;
import engine.network.server.ServerRuntime;
import engine.network.server.ServerTransport;
import engine.network.server.Session;
import engine.tracking.Tracking;
import java.util.Map;
import java.util.Optional;
import rooms.lasthour.adaptation.LastHourAdaptation;
import rooms.lasthour.recording.LastHourRecordingProtocol;
import tracking.core.TrackingJson;

/** Server-side tracking hooks for the concrete puzzles in The Last Hour. */
public final class LastHourTracking {
  private LastHourTracking() {}

  /**
   * Records a puzzle start once per running room session.
   *
   * @param puzzle room-local puzzle
   */
  public static void started(LastHourPuzzle puzzle) {
    LastHourAdaptation.started(puzzle);
    Tracking.puzzleStarted(puzzle.id());
  }

  /**
   * Records a puzzle solution once per running room session.
   *
   * @param puzzle room-local puzzle
   */
  public static void solved(LastHourPuzzle puzzle) {
    started(puzzle);
    LastHourAdaptation.solved(puzzle);
    Tracking.puzzleSolved(puzzle.id());
  }

  /**
   * Completes an adaptation episode without reporting a regular player solution.
   *
   * <p>The adaptation facade records the concrete intervention separately. This hook closes the
   * policy episode and the internal tracking state without counting the automatic completion as
   * {@code PUZZLE_SOLVED}.
   *
   * @param puzzle adaptively completed puzzle
   */
  public static void completedByAdaptation(LastHourPuzzle puzzle) {
    LastHourAdaptation.solved(puzzle);
    Tracking.completePuzzleWithoutSolvedEvent(puzzle.id());
  }

  /**
   * Records one server-evaluated answer for the acting player.
   *
   * @param puzzle room-local puzzle
   * @param objectId stable identifier of the answered object
   * @param answerKind shape of the raw answer
   * @param rawAnswer complete answer submitted by the player
   * @param correct server-evaluated correctness
   * @param player server-side player entity
   */
  public static void attempt(
      LastHourPuzzle puzzle,
      String objectId,
      String answerKind,
      String rawAnswer,
      boolean correct,
      Entity player) {
    started(puzzle);
    Tracking.participantForEntity(player.id())
        .ifPresent(
            participantId -> {
              LastHourAdaptation.attempt(puzzle, objectId, rawAnswer, correct, participantId);
              Tracking.attempt(
                  puzzle.id(), objectId, answerKind, rawAnswer, correct, participantId);
            });
  }

  /**
   * Records an E1 action candidate for the adaptation policy and persists the observed action as a
   * participant room event. The event keeps the concrete target in {@code objectId}; its payload
   * identifies the signal and action without introducing another generic tracking type.
   *
   * @param puzzle related puzzle
   * @param objectId stable object identifier
   * @param actionValue stable action value
   * @param player acting server-side player
   */
  public static void action(
      LastHourPuzzle puzzle, String objectId, String actionValue, Entity player) {
    started(puzzle);
    Tracking.participantForEntity(player.id())
        .ifPresent(
            participantId -> {
              LastHourAdaptation.action(puzzle, objectId, actionValue, participantId);
              var payload = TrackingJson.object();
              payload.put("signalKind", "action");
              payload.put("action", actionValue);
              Tracking.roomEvent(puzzle.id(), objectId, payload, participantId);
            });
  }

  /**
   * Attempts one participant-specific pilot marker at the start of the visible intro.
   *
   * @param player server-side player about to see the intro
   * @param connection current client connection, or null for singleplayer
   * @return whether the participant-specific marker was persisted
   */
  public static boolean recordingSync(Entity player, Session connection) {
    var participantId = Tracking.participantForEntity(player.id());
    if (participantId.isEmpty()) {
      return false;
    }
    return LastHourAdaptation.recordingSync(participantId.orElseThrow(), connection);
  }

  /**
   * Finds the live connection after its initial world is ready to display the intro.
   *
   * @param player authoritative player entity, which survives a client restart
   * @return ready connection, absent for disconnected players and singleplayer
   */
  public static Optional<Session> recordingConnection(Entity player) {
    if (!(Game.network() instanceof NettyNetworkHandler handler)) {
      return Optional.empty();
    }
    return handler
        .serverRuntime()
        .flatMap(ServerRuntime::transport)
        .map(ServerTransport::clientIdToSessionMap)
        .orElse(Map.of())
        .values()
        .stream()
        .filter(session -> !session.isClosed())
        .filter(
            session ->
                session
                    .clientState()
                    .filter(state -> state.initialWorldReady())
                    .flatMap(state -> state.playerEntity())
                    .filter(entity -> entity.id() == player.id())
                    .isPresent())
        .findFirst();
  }

  /**
   * Persists the local recording frame that first contained the participant's intro.
   *
   * @param player server-side player whose client acknowledged the frame
   * @param connection connection for which the intro was shown, or null for singleplayer
   * @param payload versioned recording payload received through the intro dialog
   */
  public static void recordingSyncVisible(
      Entity player, Session connection, DialogResponseMessage.Payload payload) {
    if (!Game.isSingleplayer()
        && (connection == null || recordingConnection(player).orElse(null) != connection)) {
      return;
    }
    var participantId = Tracking.participantForEntity(player.id());
    if (participantId.isEmpty()) {
      return;
    }
    LastHourRecordingProtocol.parse(payload)
        .ifPresent(
            point -> LastHourAdaptation.recordingSyncVisible(participantId.orElseThrow(), point));
  }

  /**
   * Records one meaningful use of a concrete-room hint for the acting player.
   *
   * @param puzzle room-local puzzle
   * @param hintId stable room-local hint identifier
   * @param player server-side player entity
   */
  public static void hintUsed(LastHourPuzzle puzzle, String hintId, Entity player) {
    Tracking.participantForEntity(player.id())
        .ifPresent(participantId -> Tracking.hintUsed(puzzle.id(), hintId, participantId));
  }
}
