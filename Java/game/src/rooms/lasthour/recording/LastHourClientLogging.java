package rooms.lasthour.recording;

import engine.utils.logging.DungeonLogger;
import engine.utils.logging.DungeonLoggerConfig;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/** Starts client diagnostics in the recording root without logging launch arguments or secrets. */
public final class LastHourClientLogging {
  private static final DateTimeFormatter FILE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

  private LastHourClientLogging() {}

  /** Keeps the existing warning console and writes INFO and above to a separate file per start. */
  public static void initialize() {
    Path file = logFile(Instant.now(), ProcessHandle.current().pid());
    DungeonLoggerConfig.builder()
        .consoleLevel(Level.WARNING)
        .fileLevel(Level.INFO)
        .logFile(file)
        .build();
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          DungeonLogger.getLogger(LastHourClientLogging.class)
              .error("Uncaught client error in thread {}", thread.getName(), error);
          if (previous != null) {
            previous.uncaughtException(thread, error);
          }
        });
    DungeonLogger.getLogger(LastHourClientLogging.class).info("Client log: {}", file);
  }

  static Path logFile(Instant time, long processId) {
    String study =
        FfmpegVideoRecorder.Config.sanitizeStudyId(
            System.getProperty(FfmpegVideoRecorder.STUDY_ID_PROPERTY, ""));
    if (study.isBlank()) {
      study = "unassigned";
    }
    return FfmpegVideoRecorder.Config.outputRootFromSystemProperties()
        .resolve("logs")
        .resolve("client-" + FILE_TIME.format(time) + "-" + study + "-" + processId + ".log");
  }
}
