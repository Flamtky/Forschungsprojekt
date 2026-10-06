package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LastHourClientLoggingTest {
  @TempDir Path root;

  @Test
  void usesRecordingRootAndSafeStudyIdForEveryStartup() {
    String directory = System.getProperty(FfmpegVideoRecorder.DIRECTORY_PROPERTY);
    String study = System.getProperty(FfmpegVideoRecorder.STUDY_ID_PROPERTY);
    try {
      System.setProperty(FfmpegVideoRecorder.DIRECTORY_PROPERTY, root.toString());
      System.setProperty(FfmpegVideoRecorder.STUDY_ID_PROPERTY, "P007/../private%");
      Instant time = Instant.parse("2026-09-28T20:10:39Z");
      Path first = LastHourClientLogging.logFile(time, 123);
      Path second = LastHourClientLogging.logFile(time, 456);
      assertEquals(root.resolve("logs"), first.getParent());
      assertEquals(
          "client-20260928-201039-000-P007_.._private_-123.log", first.getFileName().toString());
      assertNotEquals(first, second);
    } finally {
      restore(FfmpegVideoRecorder.DIRECTORY_PROPERTY, directory);
      restore(FfmpegVideoRecorder.STUDY_ID_PROPERTY, study);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }
}
