package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import rooms.lasthour.recording.FfmpegVideoRecorder.FinalizationSnapshot;
import rooms.lasthour.recording.FfmpegVideoRecorder.Status;

/** Tests the strict client checks performed before a recording result is sent. */
class LastHourRecordingValidationTest {
  @Test
  void acceptsACompleteMarkedDropFreeRecording() {
    assertEquals(
        "", LastHourRecording.validationFailure(snapshot(Status.COMPLETE, 0L, 1, true, false)));
  }

  @Test
  void acceptsDroppedShareBelowOnePercent() {
    assertEquals("", LastHourRecording.validationFailure(snapshot(46_675L, 58L, false)));
    assertEquals("", LastHourRecording.validationFailure(snapshot(100L, 1L, false)));
  }

  @Test
  void rejectsDroppedShareOfAtLeastOnePercent() {
    assertEquals(
        "Recording dropped 1 of 100 frames (1.00%; limit is below 1%)",
        LastHourRecording.validationFailure(snapshot(99L, 1L, false)));
    assertFalse(LastHourRecording.validationFailure(snapshot(98L, 1L, false)).isBlank());
  }

  @Test
  void exemptsRecoveredRecordingsFromDroppedShareLimit() {
    assertEquals("", LastHourRecording.validationFailure(snapshot(99L, 1L, true)));
    assertEquals("", LastHourRecording.validationFailure(snapshot(98L, 1L, true)));
  }

  @Test
  void rejectsRecorderFailureMissingMarkerAndPartialFile() {
    assertFalse(
        LastHourRecording.validationFailure(snapshot(Status.FAILED, 0L, 1, true, false)).isBlank());
    assertFalse(
        LastHourRecording.validationFailure(snapshot(Status.COMPLETE, 0L, 0, true, false))
            .isBlank());
    assertFalse(
        LastHourRecording.validationFailure(snapshot(Status.COMPLETE, 0L, 1, true, true))
            .isBlank());
  }

  private static FinalizationSnapshot snapshot(
      Status status,
      long dropped,
      int markers,
      boolean finalVideoPresent,
      boolean partialVideoPresent) {
    return snapshot(status, 200L, dropped, markers, finalVideoPresent, partialVideoPresent, false);
  }

  private static FinalizationSnapshot snapshot(long written, long dropped, boolean recovered) {
    return snapshot(Status.COMPLETE, written, dropped, 1, true, false, recovered);
  }

  private static FinalizationSnapshot snapshot(
      Status status,
      long written,
      long dropped,
      int markers,
      boolean finalVideoPresent,
      boolean partialVideoPresent,
      boolean recovered) {
    return new FinalizationSnapshot(
        UUID.fromString("bdf52978-e5e8-4661-a72f-bb4b93f35f4c"),
        status,
        "P001",
        20,
        1280,
        720,
        LastHourRecordingProtocol.FORMAT,
        10_000L,
        written,
        3L,
        dropped,
        markers,
        "windows-x86_64",
        "bundled",
        finalVideoPresent,
        partialVideoPresent,
        status == Status.FAILED ? "encoder failed" : "",
        recovered);
  }
}
