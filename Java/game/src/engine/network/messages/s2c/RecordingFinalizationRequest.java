package engine.network.messages.s2c;

import engine.network.messages.NetworkMessage;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-to-client request to stop and verify the local gameplay recording.
 *
 * @param completionId server-issued finalization identity
 * @param reason reason the game or development run ended
 * @param timeoutSeconds maximum server wait time
 * @param gameplayCompleted whether the team completed the game
 * @param recordingId recording selected at game end, empty if no visible marker exists
 */
public record RecordingFinalizationRequest(
    UUID completionId,
    String reason,
    int timeoutSeconds,
    boolean gameplayCompleted,
    String recordingId)
    implements NetworkMessage {

  /** Validates the completion identity and timeout. */
  public RecordingFinalizationRequest {
    Objects.requireNonNull(completionId, "completionId");
    reason = Objects.requireNonNullElse(reason, "");
    recordingId = Objects.requireNonNullElse(recordingId, "");
    if (timeoutSeconds < 1) {
      throw new IllegalArgumentException("timeoutSeconds must be positive");
    }
  }
}
