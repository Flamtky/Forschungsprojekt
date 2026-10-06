package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tracking.core.ReplayProtocol;

/** Local media isolation, durable answers, submission retries and authorized deletion. */
class LocalReplayTest {
  @TempDir Path directory;
  private static final String KEY = "replay-integration-test-key-long-enough";

  @Test
  void mediaDeletionIncludesInterruptedRecoveryFilesButKeepsOtherFiles() throws Exception {
    for (String name :
        List.of(
            "recording-interrupted.mkv",
            "recovered-123.mkv",
            "recovered-456.mkv",
            "decode-123.progress",
            "recovered-notes.json",
            "unrelated.mkv",
            "recording.json")) {
      Files.writeString(directory.resolve(name), "fixture");
    }
    LocalReplayFiles.deleteMedia(directory, 0);
    assertFalse(Files.exists(directory.resolve("recording-interrupted.mkv")));
    assertFalse(Files.exists(directory.resolve("recovered-123.mkv")));
    assertFalse(Files.exists(directory.resolve("recovered-456.mkv")));
    assertFalse(Files.exists(directory.resolve("decode-123.progress")));
    assertTrue(Files.exists(directory.resolve("recovered-notes.json")));
    assertTrue(Files.exists(directory.resolve("unrelated.mkv")));
    assertTrue(Files.exists(directory.resolve("recording.json")));
  }

  @Test
  void resumesBaselineAfterHelperRestartAndRejectsChanges() throws Exception {
    prepare(plan(), "http://127.0.0.1:1");
    Files.writeString(
        directory.resolve("recording.json"), "{\"status\":\"FAILED\",\"framesDropped\":58}");
    try (LocalReplayMain replay = new LocalReplayMain(directory);
        HttpClient client = HttpClient.newHttpClient()) {
      replay.start();
      awaitReady(client, replay.url());
      assertEquals(200, post(client, replay.url(), "save", responses(null)).statusCode());
      var forbidden =
          client.send(
              HttpRequest.newBuilder(URI.create(replay.url() + "state"))
                  .header("Origin", "https://unrelated.example")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(403, forbidden.statusCode());
    }
    try (LocalReplayMain replay = new LocalReplayMain(directory);
        HttpClient client = HttpClient.newHttpClient()) {
      replay.start();
      awaitReady(client, replay.url());
      assertEquals(200, get(client, replay.url() + "S1-after.mp4").statusCode());
      var changed =
          new ReplayProtocol.Responses(
              List.of(
                  new ReplayProtocol.Answer(
                      "S1", new ReplayProtocol.Baseline(5, 5, 5, 5, "HINT"), null)),
              "",
              "");
      assertEquals(400, post(client, replay.url(), "save", changed).statusCode());
      assertEquals(503, post(client, replay.url(), "close", new Object()).statusCode());
    }
  }

  @Test
  void localWorkflowPersistsGatesVideoRetriesAndDeletesOnlyMedia() throws Exception {
    var plan = plan();
    HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var available = new java.util.concurrent.atomic.AtomicBoolean();
    backend.createContext(
        "/replay/responses",
        exchange -> {
          try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            var submission = ReplayProtocol.JSON.readValue(body, ReplayProtocol.Submission.class);
            assertEquals(plan, ReplayProtocol.verify(submission.ticket(), KEY));
            ReplayProtocol.validateResponses(plan, submission.responses(), true);
            String digest =
                ReplayProtocol.hash(
                    ReplayProtocol.JSON.writeValueAsString(plan)
                        + "\n"
                        + ReplayProtocol.JSON.writeValueAsString(submission.responses()));
            byte[] response =
                ReplayProtocol.JSON
                    .createObjectNode()
                    .put("recordingId", plan.recordingId())
                    .put("submissionHash", digest)
                    .put("receiptId", UUID.randomUUID().toString())
                    .toString()
                    .getBytes();
            exchange.sendResponseHeaders(available.get() ? 200 : 503, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    backend.start();
    try {
      prepare(plan, "http://127.0.0.1:" + backend.getAddress().getPort());
      try (LocalReplayMain replay = new LocalReplayMain(directory);
          HttpClient client = HttpClient.newHttpClient()) {
        replay.start();
        awaitReady(client, replay.url());
        assertEquals(404, get(client, replay.url() + "S1-after.mp4").statusCode());
        assertEquals(
            403,
            get(client, replay.url().replaceFirst("/[0-9a-f-]+/$", "/wrong/") + "state")
                .statusCode());
        assertEquals(404, get(client, replay.url() + "recording.mkv").statusCode());
        assertEquals(404, get(client, replay.url() + "replay-launch.json").statusCode());
        assertFalse(get(client, replay.url() + "state").body().contains("HINT_OFFER"));
        var partial = responses(null);
        assertEquals(
            400,
            post(
                    client,
                    replay.url(),
                    "save",
                    responses(new ReplayProtocol.Intervention(false, null, null, null, null)))
                .statusCode());
        assertEquals(200, post(client, replay.url(), "save", partial).statusCode());
        assertEquals(200, get(client, replay.url() + "S1-after.mp4").statusCode());
        assertEquals(400, post(client, replay.url(), "submit", new Object()).statusCode());
        assertEquals(
            503,
            post(client, replay.url(), "delete", java.util.Map.of("code", "release-test"))
                .statusCode());
        assertEquals(
            200,
            post(
                    client,
                    replay.url(),
                    "save",
                    responses(new ReplayProtocol.Intervention(false, null, null, null, null)))
                .statusCode());
        assertEquals(503, post(client, replay.url(), "submit", new Object()).statusCode());
        assertTrue(Files.exists(directory.resolve("replay-state.json")));
        available.set(true);
        assertEquals(200, post(client, replay.url(), "submit", new Object()).statusCode());
        assertEquals(200, post(client, replay.url(), "submit", new Object()).statusCode());
        assertTrue(Files.exists(directory.resolve("replay-receipt.json")));
        assertEquals(503, post(client, replay.url(), "save", partial).statusCode());
        assertEquals(
            200,
            post(client, replay.url(), "delete", java.util.Map.of("code", "release-test"))
                .statusCode());
        assertFalse(Files.exists(directory.resolve("recording.mkv")));
        assertFalse(Files.exists(directory.resolve("replay-S1-before.mp4")));
        assertTrue(Files.exists(directory.resolve("unrelated.txt")));
        assertTrue(Files.exists(directory.resolve("replay-state.json")));
      }
      try (LocalReplayMain resumed = new LocalReplayMain(directory);
          HttpClient client = HttpClient.newHttpClient()) {
        resumed.start();
        awaitReady(client, resumed.url());
        String state = get(client, resumed.url() + "state").body();
        assertTrue(state.contains("\"submitted\":true"));
        assertTrue(state.contains("\"deleted\":true"));
      }
    } finally {
      backend.stop(0);
    }
  }

  @Test
  void realFfmpegProducesBrowserMp4AndSupportsRangeRequests() throws Exception {
    String ffmpeg = System.getenv("LAST_HOUR_FFMPEG_TEST");
    Assumptions.assumeTrue(ffmpeg != null && Files.isRegularFile(Path.of(ffmpeg)));
    String previous = System.getProperty(BundledFfmpeg.PATH_PROPERTY);
    System.setProperty(BundledFfmpeg.PATH_PROPERTY, ffmpeg);
    try {
      LocalReplayFiles.writeJson(
          directory.resolve("replay-launch.json"),
          new ReplayProtocol.Launch(
              "http://127.0.0.1:1",
              ReplayProtocol.sign(plan(), KEY),
              ReplayProtocol.hash("release-test")));
      Process encode =
          new ProcessBuilder(
                  ffmpeg,
                  "-nostdin",
                  "-y",
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc2=size=1280x720:rate=20",
                  "-t",
                  "2",
                  "-c:v",
                  "libx264",
                  "-preset",
                  "ultrafast",
                  directory.resolve("recording.mkv").toString())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      assertTrue(encode.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, encode.exitValue());
      try (LocalReplayMain replay = new LocalReplayMain(directory);
          HttpClient client = HttpClient.newHttpClient()) {
        replay.start();
        awaitReady(client, replay.url());
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(replay.url() + "S1-before.mp4"))
                    .header("Range", "bytes=0-31")
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, response.statusCode());
        assertEquals(32, response.body().length);
        assertEquals("ftyp", new String(response.body(), 4, 4));
      }
    } finally {
      if (previous == null) System.clearProperty(BundledFfmpeg.PATH_PROPERTY);
      else System.setProperty(BundledFfmpeg.PATH_PROPERTY, previous);
    }
  }

  private void prepare(ReplayProtocol.Plan plan, String endpoint) throws Exception {
    LocalReplayFiles.writeJson(
        directory.resolve("replay-launch.json"),
        new ReplayProtocol.Launch(
            endpoint, ReplayProtocol.sign(plan, KEY), ReplayProtocol.hash("release-test")));
    for (String file :
        List.of("recording.mkv", "replay-S1-before.mp4", "replay-S1-after.mp4", "unrelated.txt")) {
      Files.writeString(directory.resolve(file), "test fixture");
    }
  }

  private static ReplayProtocol.Plan plan() {
    return new ReplayProtocol.Plan(
        1,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "TEST",
        UUID.randomUUID().toString(),
        0,
        0,
        List.of(new ReplayProtocol.Clip("S1", 3, "E1", "", "HINT_OFFER", 1000, 0, 1000, 2000)));
  }

  private static ReplayProtocol.Responses responses(ReplayProtocol.Intervention intervention) {
    return new ReplayProtocol.Responses(
        List.of(
            new ReplayProtocol.Answer(
                "S1", new ReplayProtocol.Baseline(1, 2, 3, 4, "HINT"), intervention)),
        "",
        "");
  }

  private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      HttpClient client, String root, String route, Object body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(root + route))
            .header("X-Replay-Token", URI.create(root).getPath().split("/")[1])
            .POST(HttpRequest.BodyPublishers.ofString(ReplayProtocol.JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void awaitReady(HttpClient client, String url) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      var state = ReplayProtocol.JSON.readTree(get(client, url + "state").body());
      assertEquals("", state.path("error").stringValue());
      if (state.path("ready").booleanValue()) return;
      Thread.sleep(50);
    }
    fail("Replay did not become ready");
  }
}
