package rooms.lasthour.recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tracking.core.ReplayProtocol;

/**
 * Explicit synthetic two-client fixture for checking the browser workflow against a test backend.
 */
public final class ReplayBrowserFixture {
  private ReplayBrowserFixture() {}

  /**
   * Creates two test-pattern recordings and starts their local questionnaires.
   *
   * @param args new fixture directory and disposable backend endpoint
   * @throws Exception when fixture creation fails
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Expected new directory and test endpoint");
    Path root = Path.of(args[0]).toAbsolutePath();
    Files.createDirectory(root);
    UUID session = UUID.randomUUID();
    for (String study : List.of("SYNTHETIC-A", "SYNTHETIC-B")) {
      Path directory = Files.createDirectory(root.resolve(study));
      String recording = UUID.randomUUID().toString();
      Process process =
          new ProcessBuilder(
                  BundledFfmpeg.resolve().command(),
                  "-nostdin",
                  "-y",
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc2=size=1280x720:rate=20",
                  "-t",
                  "3",
                  "-c:v",
                  "libx264",
                  "-preset",
                  "ultrafast",
                  directory.resolve("recording.mkv").toString())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new IllegalStateException("Fixture encoding failed");
      var plan =
          new ReplayProtocol.Plan(
              1,
              session.toString(),
              UUID.randomUUID().toString(),
              study,
              recording,
              0,
              0,
              List.of(
                  new ReplayProtocol.Clip("S1", 3, "E3", "", "HINT_OFFER", 1000, 0, 1000, 3000)));
      var ticket = ReplayProtocol.sign(plan, "disposable-replay-test-api-key-with-32-characters");
      LocalReplayFiles.writeJson(
          directory.resolve("replay-launch.json"),
          new ReplayProtocol.Launch(
              args[1], ticket, ReplayProtocol.hash("synthetic-supervisor-release")));
      LocalReplayFiles.writeJson(
          directory.resolve("recording.json"),
          ReplayProtocol.JSON
              .createObjectNode()
              .put("recordingId", recording)
              .put("status", "COMPLETE"));
      System.setProperty("lasthour.replay.noBrowser", "true");
      LocalReplayMain.launch(directory, Files.readString(directory.resolve("replay-launch.json")));
      var service =
          ReplayProtocol.JSON.readTree(Files.readString(directory.resolve("replay-service.json")));
      System.out.println(study + " " + service.path("url").stringValue());
    }
  }
}
