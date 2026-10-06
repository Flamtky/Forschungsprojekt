package rooms.lasthour.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import rooms.lasthour.recording.FfmpegVideoRecorder.FinalizationSnapshot;
import rooms.lasthour.recording.FfmpegVideoRecorder.Status;
import tools.jackson.databind.node.ObjectNode;
import tracking.core.TrackingJson;

/** Finds a previous process's recording by identity and recovers a readable interrupted MKV. */
final class StoredRecording {
  private StoredRecording() {}

  static Path find(Path root, String recordingId) throws IOException {
    UUID identity = UUID.fromString(recordingId);
    try (var directories = Files.list(root)) {
      for (Path directory :
          directories.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
        try {
          var metadata =
              TrackingJson.object(
                  Files.readString(LocalReplayFiles.regular(directory, "recording.json")));
          if (identity.toString().equalsIgnoreCase(metadata.path("recordingId").asText())) {
            return directory;
          }
        } catch (IOException | RuntimeException ignored) {
          // Unrelated, incomplete metadata must not hide the requested recording.
        }
      }
    }
    throw new IOException("Recording not found: " + identity);
  }

  static FinalizationSnapshot finalizeRecording(Path directory) throws IOException {
    ObjectNode metadata =
        TrackingJson.object(
            Files.readString(LocalReplayFiles.regular(directory, "recording.json")));
    if (metadata.path("schemaVersion").asInt() != 2) {
      throw new IOException("Unsupported recording metadata");
    }
    UUID identity = UUID.fromString(metadata.path("recordingId").asText());
    Path video = directory.resolve("recording.mkv");
    Path partial = directory.resolve("recording.mkv.part");
    boolean interrupted = metadata.path("status").asText().equals("RUNNING");
    if (interrupted && !Files.exists(video)) {
      LocalReplayFiles.regular(directory, "recording.mkv.part");
      // Keep the original until remuxing and decoding have both succeeded.
      Path recovered = Files.createTempFile(directory, "recovered-", ".mkv");
      try {
        runFfmpeg(
            directory,
            List.of(
                "-y",
                "-i",
                partial.toString(),
                "-map",
                "0:v:0",
                "-c",
                "copy",
                "-f",
                "matroska",
                recovered.toString()));
        long frames = decodedFrames(directory, recovered);
        Files.move(recovered, video);
        metadata.put("videoFramesWritten", frames);
      } finally {
        Files.deleteIfExists(recovered);
      }
    }
    LocalReplayFiles.regular(directory, "recording.mkv");
    long frames = decodedFrames(directory, video);
    List<String> markers =
        Files.readAllLines(LocalReplayFiles.regular(directory, "markers.jsonl")).stream()
            .filter(line -> !line.isBlank())
            .toList();
    if (markers.size() != 1) throw new IOException("Expected exactly one stored recording marker");
    var marker = TrackingJson.object(markers.getFirst());
    if (!identity.toString().equals(marker.path("recordingId").asText())
        || marker.path("frameIndex").asLong(-1) < 0
        || marker.path("frameIndex").asLong() >= frames) {
      throw new IOException("Stored recording marker does not match the readable video");
    }
    if (interrupted) {
      // The last pre-crash metadata may predate dropped or duplicated frames.
      metadata.put("recoveredAfterCrash", true);
      metadata.put("videoFramesWritten", frames);
      metadata.put("markersWritten", markers.size());
      metadata.put("videoBytes", Files.size(video));
      metadata.put("status", "COMPLETE");
    } else if (frames != metadata.path("videoFramesWritten").asLong()
        || Files.size(video) != metadata.path("videoBytes").asLong()) {
      throw new IOException("Stored recording does not match its final metadata");
    }
    var snapshot =
        new FinalizationSnapshot(
            identity,
            Status.valueOf(metadata.path("status").asText()),
            metadata.path("studyId").asText(""),
            metadata.path("framesPerSecond").asInt(),
            metadata.path("width").asInt(),
            metadata.path("height").asInt(),
            metadata.path("format").asText(""),
            Files.size(video),
            frames,
            metadata.path("framesDuplicated").asLong(),
            metadata.path("framesDropped").asLong(),
            markers.size(),
            metadata.path("ffmpegPlatform").asText(""),
            metadata.path("ffmpegSource").asText(""),
            true,
            !interrupted && Files.exists(partial),
            metadata.path("failure").asText(""),
            metadata.path("recoveredAfterCrash").asBoolean());
    String failure = LastHourRecording.validationFailure(snapshot);
    if (!failure.isBlank()) throw new IOException(failure);
    if (interrupted) {
      // Preserve crash evidence; the recovered final video is a separate file.
      if (Files.exists(partial))
        Files.move(partial, directory.resolve("recording-interrupted.mkv"));
      LocalReplayFiles.writeJson(directory.resolve("recording.json"), metadata);
    }
    return snapshot;
  }

  private static long decodedFrames(Path directory, Path video) throws IOException {
    Path progress = Files.createTempFile(directory, "decode-", ".progress");
    try {
      runFfmpeg(
          directory,
          List.of(
              "-xerror",
              "-i",
              video.toString(),
              "-map",
              "0:v:0",
              "-progress",
              progress.toString(),
              "-f",
              "null",
              "-"));
      long frames = 0;
      for (String line : Files.readAllLines(progress)) {
        if (line.startsWith("frame=")) frames = Long.parseLong(line.substring(6).trim());
      }
      if (frames == 0) throw new IOException("Stored recording has no readable frames");
      return frames;
    } finally {
      Files.deleteIfExists(progress);
    }
  }

  private static void runFfmpeg(Path directory, List<String> arguments) throws IOException {
    var command =
        new java.util.ArrayList<>(
            List.of(BundledFfmpeg.resolve().command(), "-nostdin", "-v", "error"));
    command.addAll(arguments);
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(directory.resolve("recovery.log").toFile()))
            .start();
    try {
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly().onExit().join();
        throw new IOException("Stored recording validation timed out");
      }
      if (process.exitValue() != 0)
        throw new IOException("Stored recording is unreadable; see recovery.log");
    } catch (InterruptedException exception) {
      process.destroyForcibly().onExit().join();
      Thread.currentThread().interrupt();
      throw new IOException("Stored recording validation interrupted", exception);
    }
  }
}
