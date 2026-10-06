package rooms.lasthour.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import tracking.core.ReplayProtocol;

/** Atomic local state and narrowly scoped media deletion, shared by launcher and helper. */
final class LocalReplayFiles {
  private LocalReplayFiles() {}

  static void writeJson(Path target, Object value) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary)) {
      throw new IOException("Refusing a symbolic link for replay state");
    }
    Files.writeString(
        temporary,
        ReplayProtocol.JSON.writeValueAsString(value),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        LinkOption.NOFOLLOW_LINKS);
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static Path regular(Path directory, String name) throws IOException {
    Path file = directory.resolve(name);
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Missing local replay file: " + name);
    }
    return file;
  }

  static void deleteMedia(Path directory, int clips) throws IOException {
    // Never traverse directories or accept a browser-provided filename.
    for (String name :
        java.util.List.of(
            "recording.mkv",
            "recording.mkv.part",
            "recording-interrupted.mkv",
            "frames.jsonl",
            "markers.jsonl",
            "viewer.html",
            "ffmpeg.log")) {
      deleteRegular(directory.resolve(name));
    }
    for (int i = 1; i <= clips; i++) {
      for (String phase : java.util.List.of("before", "after")) {
        deleteRegular(directory.resolve("replay-S" + i + "-" + phase + ".mp4"));
        deleteRegular(directory.resolve("replay-S" + i + "-" + phase + ".mp4.part"));
      }
    }
    // A process crash can bypass recovery's finally blocks. Stay inside this one directory.
    try (var files = Files.newDirectoryStream(directory)) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        if ((name.startsWith("recovered-") && name.endsWith(".mkv"))
            || (name.startsWith("decode-") && name.endsWith(".progress"))) {
          deleteRegular(file);
        }
      }
    }
  }

  private static void deleteRegular(Path file) throws IOException {
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Refusing to delete non-regular replay file: " + file.getFileName());
      }
      Files.delete(file);
    }
  }
}
