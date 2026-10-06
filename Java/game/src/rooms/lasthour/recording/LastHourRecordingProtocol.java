package rooms.lasthour.recording;

import engine.network.messages.c2s.DialogResponseMessage;
import java.util.Optional;
import java.util.UUID;

/** Shared names and payload format used to pair an encoded recording frame with server tracking. */
public final class LastHourRecordingProtocol {
  /** Server event emitted immediately before the intro dialog is sent. */
  public static final String INTRO_MARKER = "pilot.recording_sync";

  /** Server event emitted after the client has sent the first intro frame to the encoder. */
  public static final String VISIBLE_SYNC_EVENT = "pilot.recording_sync_visible";

  /** Dialog callback used by the client to acknowledge a flushed encoder marker frame. */
  public static final String VISIBLE_SYNC_CALLBACK = "onRecordingSyncVisible";

  /** Stable format identifier written to local and server-side metadata. */
  public static final String FORMAT = "matroska-h264-v1";

  private static final String PAYLOAD_VERSION = "2";
  private static final int PAYLOAD_FIELD_COUNT = 7;

  private LastHourRecordingProtocol() {}

  /**
   * Describes the first encoded video frame on which a transported marker is visible.
   *
   * @param recordingId random identifier of the local recording directory
   * @param frameIndex zero-based encoded frame index
   * @param videoSyncMs frame time relative to recorder start
   * @param framesPerSecond configured capture rate
   * @param width encoded frame width
   * @param height encoded frame height
   */
  public record SyncPoint(
      UUID recordingId,
      long frameIndex,
      long videoSyncMs,
      int framesPerSecond,
      int width,
      int height) {
    /**
     * Validates all values before they are written locally or sent over the network.
     *
     * @param recordingId stable identifier of the local recording
     * @param frameIndex zero-based encoded frame index
     * @param videoSyncMs frame time relative to recorder start
     * @param framesPerSecond configured capture rate
     * @param width encoded frame width
     * @param height encoded frame height
     */
    public SyncPoint {
      if (recordingId == null) {
        throw new NullPointerException("recordingId");
      }
      if (frameIndex < 0 || videoSyncMs < 0) {
        throw new IllegalArgumentException("frameIndex and videoSyncMs must not be negative");
      }
      if (framesPerSecond < 1 || framesPerSecond > 60) {
        throw new IllegalArgumentException("framesPerSecond must be between 1 and 60");
      }
      if (width < 1 || height < 1 || width > 16_384 || height > 16_384) {
        throw new IllegalArgumentException("recording dimensions are invalid");
      }
    }

    /**
     * Encodes the fixed fields with an explicit version for the existing dialog wire protocol.
     *
     * @return versioned payload for the server callback
     */
    public DialogResponseMessage.StringList toPayload() {
      return new DialogResponseMessage.StringList(
          new String[] {
            PAYLOAD_VERSION,
            recordingId.toString(),
            Long.toString(frameIndex),
            Long.toString(videoSyncMs),
            Integer.toString(framesPerSecond),
            Integer.toString(width),
            Integer.toString(height)
          });
    }
  }

  /**
   * Parses an untrusted dialog callback payload.
   *
   * @param payload payload received by the server
   * @return validated sync point, or empty for a malformed or unsupported payload
   */
  public static Optional<SyncPoint> parse(DialogResponseMessage.Payload payload) {
    if (!(payload instanceof DialogResponseMessage.StringList list)) {
      return Optional.empty();
    }
    String[] values = list.values();
    if (values.length != PAYLOAD_FIELD_COUNT || !PAYLOAD_VERSION.equals(values[0])) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new SyncPoint(
              UUID.fromString(values[1]),
              Long.parseLong(values[2]),
              Long.parseLong(values[3]),
              Integer.parseInt(values[4]),
              Integer.parseInt(values[5]),
              Integer.parseInt(values[6])));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }
}
