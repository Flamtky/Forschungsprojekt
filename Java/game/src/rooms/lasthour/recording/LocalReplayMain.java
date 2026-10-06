package rooms.lasthour.recording;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tracking.core.ReplayProtocol;
import tracking.core.ReplayProtocol.Answer;
import tracking.core.ReplayProtocol.Launch;
import tracking.core.ReplayProtocol.Plan;
import tracking.core.ReplayProtocol.Responses;

/** Independent loopback-only replay helper. It survives the game/server shutdown. */
public final class LocalReplayMain implements AutoCloseable {
  private final Path directory;
  private final Launch launch;
  private final Plan plan;
  private final HttpServer server;
  private final String token = UUID.randomUUID().toString();
  private final FileChannel lockChannel;
  private final java.nio.channels.FileLock lock;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private Responses responses;
  private String receipt;
  private boolean deleted;
  private volatile boolean ready;
  private volatile String preparationError = "";
  private long nextDeletionAttempt;

  /**
   * Persists the handoff before starting a detached Java process; no videos are transferred.
   *
   * @param directory current local recording directory
   * @param launchJson private server handoff
   */
  static void launch(Path directory, String launchJson) throws IOException {
    Launch launch = ReplayProtocol.JSON.readValue(launchJson, Launch.class);
    Plan plan = ReplayProtocol.readPlan(launch.ticket());
    var recording =
        ReplayProtocol.JSON.readTree(Files.readString(directory.resolve("recording.json")));
    if (!recording.path("recordingId").stringValue().equals(plan.recordingId())
        || !recording.path("status").stringValue().equals("COMPLETE")) {
      throw new IOException("Replay handoff does not match the completed local recording");
    }
    LocalReplayFiles.writeJson(directory.resolve("replay-launch.json"), launch);
    boolean windows =
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
    List<String> command = new ArrayList<>(List.of(java.toString()));
    Path logs = directory.toAbsolutePath().getParent().resolve("logs");
    Files.createDirectories(logs);
    command.add("-XX:ErrorFile=" + logs.resolve("hs_err_pid%p.log"));
    String ffmpeg = System.getProperty(BundledFfmpeg.PATH_PROPERTY);
    if (ffmpeg != null) command.add("-D" + BundledFfmpeg.PATH_PROPERTY + "=" + ffmpeg);
    if (Boolean.getBoolean("lasthour.replay.noBrowser"))
      command.add("-Dlasthour.replay.noBrowser=true");
    command.addAll(
        List.of(
            "-cp",
            System.getProperty("java.class.path"),
            LocalReplayMain.class.getName(),
            directory.toAbsolutePath().toString()));
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(directory.resolve("replay-helper.log").toFile()));
    builder.environment().keySet().removeIf(name -> name.startsWith("DUNGEON_TRACKING_"));
    Process process = builder.start();
    Path service = directory.resolve("replay-service.json");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (process.isAlive() && System.nanoTime() < deadline) {
      if (Files.isRegularFile(service, LinkOption.NOFOLLOW_LINKS)
          && ReplayProtocol.JSON.readTree(Files.readString(service)).path("pid").longValue()
              == process.pid()) return;
      try {
        Thread.sleep(50);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Replay launch interrupted", exception);
      }
    }
    throw new IOException(
        "Replay helper did not start. See replay-helper.log and resume with --replay.");
  }

  /**
   * Resume with: java -cp TheLastHour.jar rooms.lasthour.recording.LocalReplayMain RECORDING_DIR.
   *
   * @param arguments one local recording directory
   */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1)
      throw new IllegalArgumentException("Expected local recording directory");
    Path directory = Path.of(arguments[0]).toRealPath();
    LocalReplayMain replay = new LocalReplayMain(directory);
    Runtime.getRuntime().addShutdownHook(new Thread(replay::close));
    replay.start();
    String url = replay.url();
    LocalReplayFiles.writeJson(
        directory.resolve("replay-service.json"),
        ReplayProtocol.JSON
            .createObjectNode()
            .put("pid", ProcessHandle.current().pid())
            .put("url", url));
    Files.writeString(
        directory.resolve("replay-open.html"),
        "<!doctype html><meta charset=utf-8><title>Replay öffnen</title><a href=\""
            + url
            + "\">Lokale Befragung öffnen</a><meta http-equiv=refresh content=\"0;url="
            + url
            + "\">");
    if (!Boolean.getBoolean("lasthour.replay.noBrowser")) {
      try {
        if (Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
          Desktop.getDesktop().browse(URI.create(url));
        } else {
          new ProcessBuilder(
                  System.getProperty("os.name").startsWith("Mac") ? "open" : "xdg-open", url)
              .start();
        }
      } catch (IOException | RuntimeException exception) {
        System.err.println("Browser could not open; open replay-open.html locally.");
      }
    }
    System.out.println("Local replay: " + url);
  }

  LocalReplayMain(Path directory) throws IOException {
    this.directory = directory.toRealPath();
    launch =
        ReplayProtocol.JSON.readValue(
            Files.readString(LocalReplayFiles.regular(this.directory, "replay-launch.json")),
            Launch.class);
    plan = ReplayProtocol.readPlan(launch.ticket());
    URI endpoint = URI.create(launch.endpoint());
    if (endpoint.getHost() == null
        || !List.of("http", "https").contains(endpoint.getScheme())
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
      throw new IOException("Invalid replay backend endpoint");
    }
    lockChannel =
        FileChannel.open(
            this.directory.resolve("replay.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    lock = lockChannel.tryLock();
    if (lock == null) {
      lockChannel.close();
      throw new IOException("Replay is already running");
    }
    responses = new Responses(List.of(), "", "");
    Path saved = this.directory.resolve("replay-state.json");
    if (Files.exists(saved)) {
      responses =
          ReplayProtocol.JSON.readValue(
              Files.readString(LocalReplayFiles.regular(this.directory, "replay-state.json")),
              Responses.class);
      ReplayProtocol.validateResponses(plan, responses, false);
    }
    Path ack = this.directory.resolve("replay-receipt.json");
    if (Files.exists(ack)) {
      receipt = Files.readString(LocalReplayFiles.regular(this.directory, "replay-receipt.json"));
      validateReceipt(receipt);
    }
    deleted = Files.exists(this.directory.resolve("replay-deleted.json"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handle);
  }

  void start() {
    server.start();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                if (!deleted && receipt == null) prepareVideos();
                ready = true;
              } catch (IOException | RuntimeException exception) {
                preparationError =
                    "Die Videoausschnitte konnten nicht vorbereitet werden. "
                        + "Bitte die Versuchsleitung informieren und replay-helper.log prüfen.";
                System.err.println(exception);
              }
            });
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + token + "/";
  }

  private void prepareVideos() throws IOException {
    Path input = LocalReplayFiles.regular(directory, "recording.mkv");
    String command = BundledFfmpeg.resolve().command();
    for (var clip : plan.clips()) {
      encode(command, input, "replay-" + clip.id() + "-before.mp4", clip.startMs(), clip.splitMs());
      if (clip.intervention()) {
        encode(command, input, "replay-" + clip.id() + "-after.mp4", clip.splitMs(), clip.endMs());
      }
    }
  }

  private void encode(String command, Path input, String name, long start, long end)
      throws IOException {
    Path target = directory.resolve(name);
    if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) > 0) return;
    Path part = directory.resolve(name + ".part");
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        || Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(target) || Files.isSymbolicLink(part))
        throw new IOException("Unsafe clip path");
    }
    Process process =
        new ProcessBuilder(
                command,
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                Double.toString(start / 1000.0),
                "-i",
                input.toString(),
                "-t",
                Double.toString((end - start) / 1000.0),
                "-an",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-threads",
                "2",
                "-pix_fmt",
                "yuv420p",
                "-movflags",
                "+faststart",
                "-f",
                "mp4",
                part.toString())
            .redirectErrorStream(true)
            .redirectOutput(
                ProcessBuilder.Redirect.appendTo(directory.resolve("replay-helper.log").toFile()))
            .start();
    try {
      if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("Replay encoding timed out");
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(exception);
    }
    if (process.exitValue() != 0 || !Files.isRegularFile(part) || Files.size(part) == 0) {
      throw new IOException("Replay encoding failed");
    }
    Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String expectedHost = "127.0.0.1:" + server.getAddress().getPort();
      String origin = exchange.getRequestHeaders().getFirst("Origin");
      String root = "/" + token + "/";
      if (!expectedHost.equals(exchange.getRequestHeaders().getFirst("Host"))
          || (origin != null && !origin.equals("http://" + expectedHost))
          || !exchange.getRequestURI().getPath().startsWith(root)) {
        send(exchange, 403, "text/plain", "Lokaler Zugriff erforderlich");
        return;
      }
      String route = exchange.getRequestURI().getPath().substring(root.length());
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      exchange
          .getResponseHeaders()
          .set(
              "Content-Security-Policy",
              "default-src 'none'; script-src 'self'; style-src 'self'; media-src 'self'; connect-src 'self'; "
                  + "img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
      try {
        if (exchange.getRequestMethod().equals("GET")) {
          switch (route) {
            case "", "replay.js", "replay.css" ->
                resource(exchange, route.isEmpty() ? "index.html" : route);
            case "state" -> send(exchange, 200, "application/json", state());
            default -> video(exchange, route);
          }
        } else if (exchange.getRequestMethod().equals("POST")) {
          if (!token.equals(exchange.getRequestHeaders().getFirst("X-Replay-Token"))) {
            send(exchange, 403, "text/plain", "Ungültiger lokaler Zugriff");
            return;
          }
          byte[] bytes = exchange.getRequestBody().readNBytes(ReplayProtocol.MAX_BODY_BYTES + 1);
          if (bytes.length > ReplayProtocol.MAX_BODY_BYTES)
            throw new IllegalArgumentException("Antwort zu groß");
          synchronized (this) {
            switch (route) {
              case "save" -> save(ReplayProtocol.JSON.readValue(bytes, Responses.class));
              case "submit" -> submit();
              case "close" -> {
                if (receipt == null)
                  throw new IOException("Bitte zuerst die Antworten übertragen.");
                send(exchange, 200, "application/json", state());
                Thread.ofVirtual().start(this::close);
                return;
              }
              case "delete" ->
                  delete(ReplayProtocol.JSON.readTree(bytes).path("code").stringValue());
              default -> {
                send(exchange, 404, "text/plain", "Nicht gefunden");
                return;
              }
            }
            send(exchange, 200, "application/json", state());
          }
        } else {
          send(exchange, 405, "text/plain", "Methode nicht erlaubt");
        }
      } catch (IllegalArgumentException | tools.jackson.core.JacksonException exception) {
        send(exchange, 400, "text/plain", "Antwort unvollständig oder ungültig.");
      } catch (IOException exception) {
        send(
            exchange,
            503,
            "text/plain",
            java.util.Objects.requireNonNullElse(
                exception.getMessage(),
                "Übertragung nicht möglich. Antworten bleiben lokal gespeichert."));
      }
    }
  }

  private synchronized String state() {
    var node =
        ReplayProtocol.JSON
            .createObjectNode()
            .put("studyId", plan.studyId())
            .put("ready", ready)
            .put("error", preparationError)
            .put("submitted", receipt != null)
            .put("deleted", deleted);
    node.set("responses", ReplayProtocol.JSON.valueToTree(responses));
    var clips = node.putArray("clips");
    for (var clip : plan.clips())
      clips
          .addObject()
          .put("id", clip.id())
          .put("expandedSupport", clip.episode().equals("E3"))
          .put("intervention", clip.intervention());
    return node.toString();
  }

  private void save(Responses next) throws IOException {
    if (!ready || deleted || receipt != null)
      throw new IOException("Befragung ist nicht bearbeitbar.");
    ReplayProtocol.validateResponses(plan, next, false);
    if (next.answers().size() < responses.answers().size()
        || next.answers().size() > responses.answers().size() + 1) {
      throw new IllegalArgumentException("Invalid questionnaire progress");
    }
    for (int i = 0; i < responses.answers().size(); i++) {
      Answer prior = responses.answers().get(i);
      Answer current = next.answers().get(i);
      if (!prior.baseline().equals(current.baseline())
          || (prior.intervention() != null
              && !prior.intervention().equals(current.intervention()))) {
        throw new IllegalArgumentException("Previously saved ratings are immutable");
      }
    }
    // A new baseline and its continuation rating may never arrive in the same request.
    if (next.answers().size() > responses.answers().size()
        && next.answers().getLast().intervention() != null) {
      throw new IllegalArgumentException("Save baseline before continuation");
    }
    LocalReplayFiles.writeJson(directory.resolve("replay-state.json"), next);
    responses = next;
  }

  private void submit() throws IOException {
    ReplayProtocol.validateResponses(plan, responses, true);
    if (receipt != null) return;
    String body =
        ReplayProtocol.JSON.writeValueAsString(
            new ReplayProtocol.Submission(launch.ticket(), responses));
    try {
      HttpResponse<java.io.InputStream> result =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(launch.endpoint().replaceAll("/+$", "") + "/replay/responses"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      String response;
      try (var input = result.body()) {
        byte[] bytes = input.readNBytes(4097);
        if (bytes.length > 4096) throw new IOException("Ungültige Serverbestätigung.");
        response = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      }
      if (result.statusCode() != 200) {
        throw new IOException(
            "Antworten sind lokal gespeichert, aber noch nicht in der Datenbank. "
                + "Übertragung erneut versuchen. Serverstatus: "
                + result.statusCode());
      }
      validateReceipt(response);
      LocalReplayFiles.writeJson(
          directory.resolve("replay-receipt.json"), ReplayProtocol.JSON.readTree(response));
      receipt = response;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Übertragung unterbrochen; Antworten bleiben lokal.", exception);
    }
  }

  private void validateReceipt(String response) throws IOException {
    var node = ReplayProtocol.JSON.readTree(response);
    String digest =
        ReplayProtocol.hash(
            ReplayProtocol.JSON.writeValueAsString(plan)
                + "\n"
                + ReplayProtocol.JSON.writeValueAsString(responses));
    if (!plan.recordingId().equals(node.path("recordingId").stringValue())
        || !digest.equals(node.path("submissionHash").stringValue())
        || node.path("receiptId").stringValue() == null)
      throw new IOException("Ungültige Serverbestätigung.");
    UUID.fromString(node.path("receiptId").stringValue());
  }

  private void delete(String code) throws IOException {
    if (receipt == null || !ready)
      throw new IOException("Löschen erst nach bestätigter Übertragung.");
    long now = System.nanoTime();
    if (now < nextDeletionAttempt) throw new IOException("Bitte fünf Sekunden warten.");
    nextDeletionAttempt = now + TimeUnit.SECONDS.toNanos(5);
    if (code == null
        || !MessageDigest.isEqual(
            ReplayProtocol.hash(code.strip()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            launch.deletionHash().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new IOException("Freigabecode stimmt nicht.");
    LocalReplayFiles.deleteMedia(directory, plan.clips().size());
    LocalReplayFiles.writeJson(
        directory.resolve("replay-deleted.json"),
        ReplayProtocol.JSON
            .createObjectNode()
            .put("deletedAt", java.time.Instant.now().toString()));
    deleted = true;
  }

  private void resource(HttpExchange exchange, String name) throws IOException {
    try (var input = LocalReplayMain.class.getResourceAsStream("/replay/" + name)) {
      if (input == null) throw new IOException("Replay-Oberfläche fehlt im Spielpaket.");
      send(
          exchange,
          200,
          name.endsWith(".js")
              ? "text/javascript"
              : name.endsWith(".css") ? "text/css" : "text/html",
          new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private void video(HttpExchange exchange, String route) throws IOException {
    Path file = null;
    synchronized (this) {
      if (!ready || deleted) {
        send(exchange, 404, "text/plain", "Video nicht verfügbar");
        return;
      }
      for (int i = 0; i < plan.clips().size(); i++) {
        var clip = plan.clips().get(i);
        if (route.equals(clip.id() + "-before.mp4") && i <= responses.answers().size()) {
          file = LocalReplayFiles.regular(directory, "replay-" + route);
        }
        if (route.equals(clip.id() + "-after.mp4")
            && clip.intervention()
            && i < responses.answers().size())
          file = LocalReplayFiles.regular(directory, "replay-" + route);
      }
    }
    if (file == null) {
      send(exchange, 404, "text/plain", "Video noch nicht freigegeben");
      return;
    }
    long size = Files.size(file), start = 0, end = size - 1;
    String range = exchange.getRequestHeaders().getFirst("Range");
    if (range != null) {
      var matcher = java.util.regex.Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
      if (!matcher.matches()) {
        exchange.sendResponseHeaders(416, -1);
        return;
      }
      start = Long.parseLong(matcher.group(1));
      if (!matcher.group(2).isEmpty()) end = Math.min(end, Long.parseLong(matcher.group(2)));
      if (start > end) {
        exchange.sendResponseHeaders(416, -1);
        return;
      }
      exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
    }
    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
    exchange.getResponseHeaders().set("Content-Type", "video/mp4");
    exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1);
    try (var input = Files.newInputStream(file)) {
      input.skipNBytes(start);
      byte[] buffer = new byte[65536];
      long left = end - start + 1;
      while (left > 0) {
        int count = input.read(buffer, 0, (int) Math.min(left, buffer.length));
        if (count < 0) break;
        exchange.getResponseBody().write(buffer, 0, count);
        left -= count;
      }
    }
  }

  private static void send(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
    http.close();
    try {
      lock.release();
      lockChannel.close();
    } catch (IOException ignored) {
      /* Process is exiting. */
    }
  }
}
