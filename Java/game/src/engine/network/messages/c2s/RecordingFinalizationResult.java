package engine.network.messages.c2s;

import engine.network.messages.NetworkMessage;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-to-server report for one finalized local gameplay recording.
 *
 * @param completionId server-issued finalization identity
 * @param status local recorder outcome
 * @param studyId pseudonymous study identity configured on the client
 * @param recordingId local recording identity
 * @param framesPerSecond configured video frame rate
 * @param width encoded video width
 * @param height encoded video height
 * @param format versioned recording container and codec profile
 * @param videoBytes size of the finalized MKV
 * @param framesWritten number of encoded video frames
 * @param framesDuplicated number of frames inserted to preserve timing
 * @param framesDropped number of source frames rejected by the bounded queue
 * @param markersWritten number of synchronized recording markers
 * @param ffmpegPlatform selected FFmpeg platform
 * @param ffmpegSource source used to resolve FFmpeg
 * @param failure validation or encoder failure, otherwise empty
 * @param recoveredAfterCrash whether recovery left dropped/duplicated counters unreliable
 */
public record RecordingFinalizationResult(
    UUID completionId,
    Status status,
    String studyId,
    String recordingId,
    int framesPerSecond,
    int width,
    int height,
    String format,
    long videoBytes,
    long framesWritten,
    long framesDuplicated,
    long framesDropped,
    int markersWritten,
    String ffmpegPlatform,
    String ffmpegSource,
    String failure,
    boolean recoveredAfterCrash)
    implements NetworkMessage {

  /** Final state reported by the local recorder. */
  public enum Status {
    COMPLETE,
    FAILED
  }

  /** Validates required fields and non-negative counters. */
  public RecordingFinalizationResult {
    Objects.requireNonNull(completionId, "completionId");
    Objects.requireNonNull(status, "status");
    studyId = Objects.requireNonNullElse(studyId, "");
    recordingId = Objects.requireNonNullElse(recordingId, "");
    format = Objects.requireNonNullElse(format, "");
    ffmpegPlatform = Objects.requireNonNullElse(ffmpegPlatform, "");
    ffmpegSource = Objects.requireNonNullElse(ffmpegSource, "");
    failure = Objects.requireNonNullElse(failure, "");
    if (framesPerSecond < 0
        || width < 0
        || height < 0
        || videoBytes < 0L
        || framesWritten < 0L
        || framesDuplicated < 0L
        || framesDropped < 0L
        || markersWritten < 0) {
      throw new IllegalArgumentException("Recording finalization counters must not be negative");
    }
  }

  /** Returns whether this result satisfies the replay requirements for a pilot run. */
  public boolean usableForReplay() {
    return status == Status.COMPLETE
        && !studyId.isBlank()
        && !recordingId.isBlank()
        && (framesPerSecond == 20 || framesPerSecond == 30)
        && width == 1280
        && height == 720
        && format.equals("matroska-h264-v1")
        && videoBytes > 0L
        && framesWritten > 0L
        && (recoveredAfterCrash || framesDropped * 100 < framesWritten + framesDropped)
        && markersWritten == 1
        && java.util.Set.of(
                "windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64")
            .contains(ffmpegPlatform)
        && ffmpegSource.equals("bundled")
        && failure.isBlank();
  }
}
