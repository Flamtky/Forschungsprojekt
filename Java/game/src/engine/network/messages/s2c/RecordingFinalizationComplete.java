package engine.network.messages.s2c;

import engine.network.messages.NetworkMessage;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-to-client confirmation that the coordinated run shutdown can finish.
 *
 * @param completionId server-issued finalization identity
 * @param runStatus overall pilot run outcome
 * @param message human-readable outcome
 * @param replayLaunchJson private handoff for the addressed client, or empty
 */
public record RecordingFinalizationComplete(
    UUID completionId, RunStatus runStatus, String message, String replayLaunchJson)
    implements NetworkMessage {

  /** Overall usability of the finished pilot run. */
  public enum RunStatus {
    COMPLETE,
    INVALID,
    ABORTED
  }

  /** Validates required fields and normalizes the optional human-readable message. */
  public RecordingFinalizationComplete {
    Objects.requireNonNull(completionId, "completionId");
    Objects.requireNonNull(runStatus, "runStatus");
    message = Objects.requireNonNullElse(message, "");
    replayLaunchJson = Objects.requireNonNullElse(replayLaunchJson, "");
  }

  /**
   * Creates an outcome without a replay handoff.
   *
   * @param completionId finalization identity
   * @param runStatus run outcome
   * @param message operator message
   */
  public RecordingFinalizationComplete(UUID completionId, RunStatus runStatus, String message) {
    this(completionId, runStatus, message, "");
  }
}
