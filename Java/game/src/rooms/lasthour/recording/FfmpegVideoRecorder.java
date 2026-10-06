package rooms.lasthour.recording;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import rooms.lasthour.recording.BundledFfmpeg.ResolvedFfmpeg;
import rooms.lasthour.recording.LastHourRecordingProtocol.SyncPoint;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes a synchronized H.264 Matroska recording through a bundled FFmpeg process. */
final class FfmpegVideoRecorder implements AutoCloseable {
  static final String ENABLED_PROPERTY = "lasthour.recording.enabled";
  static final String DIRECTORY_PROPERTY = "lasthour.recording.dir";
  static final String STUDY_ID_PROPERTY = "lasthour.recording.studyId";
  static final String FPS_PROPERTY = "lasthour.recording.fps";
  static final String WIDTH_PROPERTY = "lasthour.recording.width";
  static final String HEIGHT_PROPERTY = "lasthour.recording.height";
  static final String CRF_PROPERTY = "lasthour.recording.crf";
  static final String PRESET_PROPERTY = "lasthour.recording.preset";
  static final String ENCODER_THREADS_PROPERTY = "lasthour.recording.encoderThreads";
  static final String QUEUE_PROPERTY = "lasthour.recording.queueFrames";
  static final String DIRECTORY_ENVIRONMENT = "LAST_HOUR_RECORDING_DIR";

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter DIRECTORY_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final long CLOSE_TIMEOUT_MILLIS = 60_000L;

  private final Config config;
  private final UUID recordingId;
  private final Instant startedAt;
  private final long startedNanos;
  private final LongSupplier nanoClock;
  private final Supplier<Instant> wallClock;
  private final FrameScaler scaler;
  private final VideoSink sink;
  private final Consumer<MarkerFrame> markerConsumer;
  private final Path directory;
  private final Path framesIndex;
  private final Path markersIndex;
  private final BlockingQueue<FrameJob> queue;
  private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicBoolean closeStarted = new AtomicBoolean(false);
  private final AtomicLong sourceFramesCaptured = new AtomicLong();
  private final AtomicLong videoFramesWritten = new AtomicLong();
  private final AtomicLong framesDuplicated = new AtomicLong();
  private final AtomicLong framesDropped = new AtomicLong();
  private final AtomicLong markersWritten = new AtomicLong();
  private final AtomicLong renderFramesObserved = new AtomicLong();
  private final AtomicLong lastRenderNanos = new AtomicLong(-1L);
  private final AtomicLong totalRenderIntervalNanos = new AtomicLong();
  private final AtomicLong maximumRenderIntervalNanos = new AtomicLong();
  private final AtomicLong renderIntervalsOver33Millis = new AtomicLong();
  private final AtomicLong renderIntervalsOver50Millis = new AtomicLong();
  private final AtomicLong renderIntervalsOver100Millis = new AtomicLong();
  private final AtomicLong framebufferCaptures = new AtomicLong();
  private final AtomicLong totalFramebufferCaptureNanos = new AtomicLong();
  private final AtomicLong maximumFramebufferCaptureNanos = new AtomicLong();
  private final Object captureLock = new Object();
  private final Object summaryLock = new Object();
  private final Map<String, MarkerRequest> pendingMarkers = new LinkedHashMap<>();
  private final java.util.Set<String> requestedMarkers = new java.util.HashSet<>();
  private final List<MarkerFrame> markerFrames = new ArrayList<>();
  private final Thread worker;

  private long lastCaptureSlot = -1L;
  private long nextCaptureIndex;
  private volatile String username = "";
  private volatile String failureMessage = "";
  private volatile Instant endedAt;

  private FfmpegVideoRecorder(
      Config config,
      LongSupplier nanoClock,
      Supplier<Instant> wallClock,
      FrameScaler scaler,
      VideoSinkFactory sinkFactory,
      Consumer<MarkerFrame> markerConsumer)
      throws IOException {
    this.config = Objects.requireNonNull(config, "config");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    this.scaler = Objects.requireNonNull(scaler, "scaler");
    this.markerConsumer = Objects.requireNonNull(markerConsumer, "markerConsumer");
    recordingId = UUID.randomUUID();
    startedAt = wallClock.get();
    startedNanos = nanoClock.getAsLong();
    queue = new ArrayBlockingQueue<>(config.queueFrames());

    Files.createDirectories(config.outputRoot());
    String label = config.studyId().isBlank() ? "client" : config.studyId();
    String directoryName =
        DIRECTORY_TIME.format(startedAt)
            + "_"
            + label
            + "_"
            + recordingId.toString().substring(0, 8);
    directory = uniqueDirectory(config.outputRoot(), directoryName);
    Files.createDirectories(directory);
    verifyDirectory(directory);

    framesIndex = directory.resolve("frames.jsonl");
    markersIndex = directory.resolve("markers.jsonl");
    Files.createFile(framesIndex);
    Files.createFile(markersIndex);
    writeSummary();

    try {
      sink = sinkFactory.open(config, directory);
    } catch (IOException | RuntimeException exception) {
      failureMessage =
          exception.getMessage() == null ? exception.toString() : exception.getMessage();
      status.set(Status.FAILED);
      writeSummaryQuietly();
      throw exception;
    }

    worker = new Thread(this::encodeLoop, "last-hour-ffmpeg-writer");
    worker.setDaemon(true);
    worker.start();
  }

  static FfmpegVideoRecorder start(Config config, Consumer<MarkerFrame> markerConsumer)
      throws IOException {
    return new FfmpegVideoRecorder(
        config,
        java.lang.System::nanoTime,
        Instant::now,
        new NearestNeighborBgrScaler(),
        FfmpegVideoSink::open,
        markerConsumer);
  }

  static FfmpegVideoRecorder start(
      Config config,
      LongSupplier nanoClock,
      Supplier<Instant> wallClock,
      FrameScaler scaler,
      VideoSinkFactory sinkFactory,
      Consumer<MarkerFrame> markerConsumer)
      throws IOException {
    return new FfmpegVideoRecorder(
        config, nanoClock, wallClock, scaler, sinkFactory, markerConsumer);
  }

  /**
   * Queues the current framebuffer when the configured video frame slot is due.
   *
   * @param frameSource supplies the rendered framebuffer when due, or null during a display change
   * @return whether the frame entered the bounded writer queue
   */
  boolean captureIfDue(Supplier<RawFrame> frameSource) {
    Objects.requireNonNull(frameSource, "frameSource");
    if (status.get() != Status.RUNNING || !accepting.get()) {
      return false;
    }

    long nowNanos = nanoClock.getAsLong();
    observeRenderFrame(nowNanos);
    long elapsedNanos = Math.max(0L, nowNanos - startedNanos);
    long intervalNanos = TimeUnit.SECONDS.toNanos(1L) / config.framesPerSecond();
    long slot = elapsedNanos / intervalNanos;
    synchronized (captureLock) {
      if (slot <= lastCaptureSlot || status.get() != Status.RUNNING) {
        return false;
      }
      lastCaptureSlot = slot;
    }

    RawFrame rawFrame;
    long captureStartedNanos = System.nanoTime();
    try {
      rawFrame = frameSource.get();
      if (rawFrame == null) {
        // No source frame existed during the display change. The next frame fills these slots
        // with duplicates; this is not encoder overload or loss of a captured frame.
        return false;
      }
      rawFrame.validate();
    } catch (RuntimeException exception) {
      fail("Framebuffer capture failed", exception);
      return false;
    } finally {
      observeFramebufferCapture(System.nanoTime() - captureStartedNanos);
    }

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    synchronized (captureLock) {
      if (!accepting.get() || status.get() != Status.RUNNING) {
        return false;
      }
      List<MarkerRequest> markers = List.copyOf(pendingMarkers.values());
      FrameJob job =
          new FrameJob(nextCaptureIndex, slot, elapsedMillis, wallClock.get(), rawFrame, markers);
      if (!queue.offer(job)) {
        framesDropped.incrementAndGet();
        return false;
      }
      nextCaptureIndex++;
      sourceFramesCaptured.incrementAndGet();
      markers.forEach(marker -> pendingMarkers.remove(marker.marker()));
      return true;
    }
  }

  void markNextFrame(String marker, String dialogId) {
    if (marker == null || marker.isBlank() || dialogId == null || dialogId.isBlank()) {
      return;
    }
    synchronized (captureLock) {
      if (status.get() == Status.RUNNING && requestedMarkers.add(marker)) {
        pendingMarkers.put(marker, new MarkerRequest(marker, dialogId));
      }
    }
  }

  void connected(String username) {
    this.username = username == null ? "" : username;
    writeSummaryQuietly();
  }

  Status status() {
    return status.get();
  }

  int framesPerSecond() {
    return config.framesPerSecond();
  }

  Path directory() {
    return directory;
  }

  UUID recordingId() {
    return recordingId;
  }

  long framesWritten() {
    return videoFramesWritten.get();
  }

  long framesDropped() {
    return framesDropped.get();
  }

  String failureMessage() {
    return failureMessage;
  }

  FinalizationSnapshot finalizationSnapshot() {
    Path video = directory.resolve("recording.mkv");
    Path partialVideo = directory.resolve("recording.mkv.part");
    long videoBytes = 0L;
    String snapshotFailure = failureMessage;
    try {
      if (Files.isRegularFile(video)) {
        videoBytes = Files.size(video);
      }
    } catch (IOException exception) {
      if (snapshotFailure.isBlank()) {
        snapshotFailure = "Could not inspect finalized recording: " + exception.getMessage();
      }
    }
    if (status.get() == Status.COMPLETE && snapshotFailure.isBlank()) {
      snapshotFailure = finalMetadataFailure(videoBytes);
    }
    return new FinalizationSnapshot(
        recordingId,
        status.get(),
        config.studyId(),
        config.framesPerSecond(),
        config.width(),
        config.height(),
        LastHourRecordingProtocol.FORMAT,
        videoBytes,
        videoFramesWritten.get(),
        framesDuplicated.get(),
        framesDropped.get(),
        Math.toIntExact(markersWritten.get()),
        config.ffmpeg().platform().id(),
        config.ffmpeg().source(),
        Files.isRegularFile(video),
        Files.exists(partialVideo),
        snapshotFailure,
        false);
  }

  private String finalMetadataFailure(long videoBytes) {
    Path metadata = directory.resolve("recording.json");
    try {
      if (!Files.isRegularFile(metadata)) {
        return "Final recording.json is missing";
      }
      tools.jackson.databind.JsonNode root = JSON.readTree(Files.readString(metadata));
      if (root == null
          || root.path("schemaVersion").asInt() != 2
          || !recordingId.toString().equals(root.path("recordingId").asText())
          || !Status.COMPLETE.name().equals(root.path("status").asText())
          || !config.studyId().equals(root.path("studyId").asText())
          || config.framesPerSecond() != root.path("framesPerSecond").asInt()
          || videoFramesWritten.get() != root.path("videoFramesWritten").asLong()
          || framesDropped.get() != root.path("framesDropped").asLong()
          || markersWritten.get() != root.path("markersWritten").asLong()
          || videoBytes != root.path("videoBytes").asLong()) {
        return "Final recording.json does not match the completed recording";
      }
      return "";
    } catch (IOException exception) {
      return "Could not validate final recording.json: " + exception.getMessage();
    }
  }

  @Override
  public void close() {
    if (!closeStarted.compareAndSet(false, true)) {
      return;
    }
    synchronized (captureLock) {
      accepting.set(false);
    }
    try {
      worker.join(CLOSE_TIMEOUT_MILLIS);
      if (worker.isAlive()) {
        worker.interrupt();
        fail("FFmpeg writer did not stop within 60 seconds", null);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      fail("Interrupted while stopping FFmpeg writer", exception);
    }

    endedAt = wallClock.get();
    if (status.get() == Status.RUNNING && videoFramesWritten.get() == 0L) {
      fail("No video frame was written", null);
    }
    status.compareAndSet(Status.RUNNING, Status.COMPLETE);
    if (status.get() == Status.COMPLETE) {
      try {
        writeViewer();
      } catch (IOException exception) {
        fail("Could not write recording viewer", exception);
      }
    }
    try {
      writeSummary();
    } catch (IOException exception) {
      fail("Could not write final recording metadata", exception);
    }
  }

  private void encodeLoop() {
    try (VideoSink activeSink = sink;
        BufferedWriter frameWriter =
            Files.newBufferedWriter(framesIndex, StandardOpenOption.APPEND);
        BufferedWriter markerWriter =
            Files.newBufferedWriter(markersIndex, StandardOpenOption.APPEND)) {
      byte[] previousFrame = null;
      long lastWrittenSlot = -1L;
      while (accepting.get() || !queue.isEmpty()) {
        FrameJob job = queue.poll(100L, TimeUnit.MILLISECONDS);
        if (job == null) {
          continue;
        }

        byte[] currentFrame = scaler.scale(job.rawFrame(), config.width(), config.height());
        byte[] filler = previousFrame == null ? currentFrame : previousFrame;
        long duplicateStart = lastWrittenSlot + 1L;
        for (long frameSlot = duplicateStart; frameSlot < job.videoFrameIndex(); frameSlot++) {
          activeSink.writeFrame(filler);
          videoFramesWritten.incrementAndGet();
          framesDuplicated.incrementAndGet();
        }

        activeSink.writeFrame(currentFrame);
        videoFramesWritten.incrementAndGet();
        writeFrameEntry(frameWriter, job, config.framesPerSecond());
        for (MarkerRequest marker : job.markers()) {
          activeSink.flush();
          MarkerFrame markerFrame = markerFrame(marker, job.videoFrameIndex());
          writeMarkerEntry(markerWriter, markerFrame);
          markerWriter.flush();
          markerFrames.add(markerFrame);
          markersWritten.incrementAndGet();
          markerConsumer.accept(markerFrame);
        }
        previousFrame = currentFrame;
        lastWrittenSlot = job.videoFrameIndex();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      fail("FFmpeg writer was interrupted", exception);
    } catch (IOException | RuntimeException exception) {
      fail("FFmpeg video recording failed", exception);
    }
  }

  private MarkerFrame markerFrame(MarkerRequest marker, long frameIndex) {
    SyncPoint point =
        new SyncPoint(
            recordingId,
            frameIndex,
            videoTimeMillis(frameIndex, config.framesPerSecond()),
            config.framesPerSecond(),
            config.width(),
            config.height());
    return new MarkerFrame(marker.marker(), marker.dialogId(), point);
  }

  private static long videoTimeMillis(long frameIndex, int framesPerSecond) {
    return Math.multiplyExact(frameIndex, 1_000L) / framesPerSecond;
  }

  private static Path uniqueDirectory(Path root, String directoryName) throws IOException {
    for (int suffix = 0; suffix < 1_000; suffix++) {
      Path candidate = root.resolve(suffix == 0 ? directoryName : directoryName + "_" + suffix);
      try {
        return Files.createDirectory(candidate);
      } catch (java.nio.file.FileAlreadyExistsException ignored) {
        // A second recording can start within the same millisecond in deterministic tests.
      }
    }
    throw new IOException("Could not allocate a unique recording directory");
  }

  private static void verifyDirectory(Path directory) throws IOException {
    Path probe = directory.resolve(".write-test");
    byte[] expected = {0x4c, 0x48};
    Files.write(probe, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    byte[] actual = Files.readAllBytes(probe);
    if (!Arrays.equals(expected, actual)) {
      throw new IOException("Recording directory write test returned different bytes");
    }
    Files.delete(probe);
  }

  private void fail(String message, Throwable cause) {
    if (status.getAndSet(Status.FAILED) != Status.FAILED) {
      accepting.set(false);
      queue.clear();
      failureMessage =
          cause == null || cause.getMessage() == null
              ? message
              : message + ": " + cause.getMessage();
      writeSummaryQuietly();
    }
  }

  private void writeSummaryQuietly() {
    try {
      writeSummary();
    } catch (IOException ignored) {
      // A metadata update must not terminate the game or hide the original recorder error.
    }
  }

  private void writeSummary() throws IOException {
    synchronized (summaryLock) {
      ObjectNode root = JSON.createObjectNode();
      root.put("schemaVersion", 2);
      root.put("recordingId", recordingId.toString());
      root.put("status", status.get().name());
      root.put("format", LastHourRecordingProtocol.FORMAT);
      root.put("container", "matroska");
      root.put("codec", "h264");
      root.put("videoFile", "recording.mkv");
      root.put("framesPerSecond", config.framesPerSecond());
      root.put("width", config.width());
      root.put("height", config.height());
      root.put("crf", config.crf());
      root.put("preset", config.preset());
      root.put("encoderThreads", config.encoderThreads());
      root.put("startedAt", startedAt.toString());
      if (endedAt != null) {
        root.put("endedAt", endedAt.toString());
      }
      if (!config.studyId().isBlank()) {
        root.put("studyId", config.studyId());
      }
      if (!username.isBlank()) {
        root.put("username", username);
      }
      root.put("sourceFramesCaptured", sourceFramesCaptured.get());
      root.put("videoFramesWritten", videoFramesWritten.get());
      root.put("framesDuplicated", framesDuplicated.get());
      root.put("framesDropped", framesDropped.get());
      root.put("markersWritten", markersWritten.get());
      root.put("renderFramesObserved", renderFramesObserved.get());
      root.put("averageRenderFramesPerSecond", averageRenderFramesPerSecond());
      root.put("maximumRenderFrameIntervalMs", nanosToMillis(maximumRenderIntervalNanos.get()));
      root.put("renderIntervalsOver33Ms", renderIntervalsOver33Millis.get());
      root.put("renderIntervalsOver50Ms", renderIntervalsOver50Millis.get());
      root.put("renderIntervalsOver100Ms", renderIntervalsOver100Millis.get());
      root.put("framebufferCaptures", framebufferCaptures.get());
      root.put("averageFramebufferCaptureMs", averageFramebufferCaptureMillis());
      root.put("maximumFramebufferCaptureMs", nanosToMillis(maximumFramebufferCaptureNanos.get()));
      root.put("ffmpegPlatform", config.ffmpeg().platform().id());
      root.put("ffmpegSource", config.ffmpeg().source());
      root.put("ffmpegVersion", config.ffmpeg().version());
      Path video = directory.resolve("recording.mkv");
      if (Files.isRegularFile(video)) {
        root.put("videoBytes", Files.size(video));
      }
      if (!failureMessage.isBlank()) {
        root.put("failure", failureMessage);
      }
      writeAtomically(directory.resolve("recording.json"), encode(root));
    }
  }

  private void writeViewer() throws IOException {
    ArrayNode markers = JSON.createArrayNode();
    for (MarkerFrame marker : markerFrames) {
      ObjectNode node = markers.addObject();
      node.put("marker", marker.marker());
      node.put("elapsedMs", marker.point().videoSyncMs());
      node.put("frameIndex", marker.point().frameIndex());
    }
    String html =
        """
        <!doctype html>
        <html lang="de">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>The Last Hour Aufnahme</title>
          <style>
            body { margin: 0; background: #111; color: #eee; font: 16px system-ui, sans-serif; }
            main { max-width: 1280px; margin: 0 auto; padding: 20px; }
            video { display: block; width: 100%%; max-height: 75vh; background: #000; }
            button { margin: 12px 8px 0 0; padding: 7px 12px; }
          </style>
        </head>
        <body><main>
          <video id="video" controls preload="metadata" src="recording.mkv"></video>
          <div id="markers"></div>
          <p>Falls der Browser Matroska nicht abspielt, recording.mkv in einem lokalen Videoplayer öffnen.</p>
        </main>
        <script>
          const markers = %s;
          const video = document.querySelector('#video');
          const markerBox = document.querySelector('#markers');
          for (const marker of markers) {
            const button = document.createElement('button');
            button.textContent = `${marker.marker} @ ${(marker.elapsedMs / 1000).toFixed(3)} s`;
            button.addEventListener('click', () => { video.currentTime = marker.elapsedMs / 1000; });
            markerBox.appendChild(button);
          }
        </script>
        </body></html>
        """
            .formatted(encode(markers));
    Files.writeString(
        directory.resolve("viewer.html"),
        html,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  private static void writeFrameEntry(BufferedWriter writer, FrameJob job, int framesPerSecond)
      throws IOException {
    ObjectNode node = JSON.createObjectNode();
    node.put("captureIndex", job.captureIndex());
    node.put("frameIndex", job.videoFrameIndex());
    node.put("videoMs", videoTimeMillis(job.videoFrameIndex(), framesPerSecond));
    node.put("captureElapsedMs", job.captureElapsedMillis());
    node.put("capturedAt", job.capturedAt().toString());
    writer.write(encode(node));
    writer.newLine();
  }

  private static void writeMarkerEntry(BufferedWriter writer, MarkerFrame marker)
      throws IOException {
    ObjectNode node = JSON.createObjectNode();
    node.put("marker", marker.marker());
    node.put("dialogId", marker.dialogId());
    node.put("recordingId", marker.point().recordingId().toString());
    node.put("frameIndex", marker.point().frameIndex());
    node.put("videoSyncMs", marker.point().videoSyncMs());
    node.put("framesPerSecond", marker.point().framesPerSecond());
    node.put("width", marker.point().width());
    node.put("height", marker.point().height());
    writer.write(encode(node));
    writer.newLine();
  }

  private static String encode(tools.jackson.databind.JsonNode node) throws IOException {
    try {
      return JSON.writeValueAsString(node);
    } catch (JacksonException exception) {
      throw new IOException("Could not encode recording metadata", exception);
    }
  }

  private static void writeAtomically(Path target, String contents) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        contents,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void observeRenderFrame(long nowNanos) {
    renderFramesObserved.incrementAndGet();
    long previous = lastRenderNanos.getAndSet(nowNanos);
    if (previous < 0L || nowNanos <= previous) {
      return;
    }
    long interval = nowNanos - previous;
    totalRenderIntervalNanos.addAndGet(interval);
    maximumRenderIntervalNanos.accumulateAndGet(interval, Math::max);
    if (interval > TimeUnit.MILLISECONDS.toNanos(33L)) {
      renderIntervalsOver33Millis.incrementAndGet();
    }
    if (interval > TimeUnit.MILLISECONDS.toNanos(50L)) {
      renderIntervalsOver50Millis.incrementAndGet();
    }
    if (interval > TimeUnit.MILLISECONDS.toNanos(100L)) {
      renderIntervalsOver100Millis.incrementAndGet();
    }
  }

  private void observeFramebufferCapture(long durationNanos) {
    long duration = Math.max(0L, durationNanos);
    framebufferCaptures.incrementAndGet();
    totalFramebufferCaptureNanos.addAndGet(duration);
    maximumFramebufferCaptureNanos.accumulateAndGet(duration, Math::max);
  }

  private double averageRenderFramesPerSecond() {
    long intervals = Math.max(0L, renderFramesObserved.get() - 1L);
    long elapsed = totalRenderIntervalNanos.get();
    return elapsed == 0L ? 0.0 : intervals * 1_000_000_000.0 / elapsed;
  }

  private double averageFramebufferCaptureMillis() {
    long captures = framebufferCaptures.get();
    return captures == 0L ? 0.0 : nanosToMillis(totalFramebufferCaptureNanos.get()) / captures;
  }

  private static double nanosToMillis(long nanos) {
    return nanos / 1_000_000.0;
  }

  enum Status {
    RUNNING,
    COMPLETE,
    FAILED
  }

  record FinalizationSnapshot(
      UUID recordingId,
      Status status,
      String studyId,
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
      boolean finalVideoPresent,
      boolean partialVideoPresent,
      String failure,
      boolean recoveredAfterCrash) {}

  record Config(
      Path outputRoot,
      String studyId,
      int framesPerSecond,
      int width,
      int height,
      int crf,
      String preset,
      int encoderThreads,
      int queueFrames,
      ResolvedFfmpeg ffmpeg) {
    Config {
      outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
      studyId = sanitizeStudyId(studyId);
      preset = Objects.requireNonNull(preset, "preset").trim().toLowerCase(java.util.Locale.ROOT);
      ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
      if (framesPerSecond < 1 || framesPerSecond > 60) {
        throw new IllegalArgumentException("framesPerSecond must be between 1 and 60");
      }
      if (width < 2 || height < 2 || width > 16_384 || height > 16_384) {
        throw new IllegalArgumentException("recording dimensions are invalid");
      }
      if (width % 2 != 0 || height % 2 != 0) {
        throw new IllegalArgumentException("H.264 recording dimensions must be even");
      }
      if (crf < 0 || crf > 51) {
        throw new IllegalArgumentException("crf must be between 0 and 51");
      }
      if (!List.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium")
          .contains(preset)) {
        throw new IllegalArgumentException("unsupported H.264 preset: " + preset);
      }
      if (encoderThreads < 1 || encoderThreads > 16) {
        throw new IllegalArgumentException("encoderThreads must be between 1 and 16");
      }
      if (queueFrames < 1 || queueFrames > 600) {
        throw new IllegalArgumentException("queueFrames must be between 1 and 600");
      }
    }

    static Config fromSystemProperties() throws IOException {
      int fps = integerProperty(FPS_PROPERTY, 20);
      return new Config(
          outputRootFromSystemProperties(),
          System.getProperty(STUDY_ID_PROPERTY, ""),
          fps,
          integerProperty(WIDTH_PROPERTY, 1280),
          integerProperty(HEIGHT_PROPERTY, 720),
          integerProperty(CRF_PROPERTY, 23),
          System.getProperty(PRESET_PROPERTY, "veryfast"),
          integerProperty(ENCODER_THREADS_PROPERTY, 2),
          integerProperty(QUEUE_PROPERTY, Math.max(4, fps / 2)),
          BundledFfmpeg.resolve());
    }

    static Path outputRootFromSystemProperties() {
      String configuredDirectory = System.getProperty(DIRECTORY_PROPERTY, "").trim();
      if (configuredDirectory.isBlank()) {
        configuredDirectory = System.getenv().getOrDefault(DIRECTORY_ENVIRONMENT, "").trim();
      }
      if (configuredDirectory.isBlank()) {
        Path codeLocation = null;
        try {
          codeLocation =
              Path.of(
                  FfmpegVideoRecorder.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI());
        } catch (java.net.URISyntaxException | RuntimeException ignored) {
          // Development launches or restricted runtimes may not have a JAR location.
        }
        return defaultOutputRoot(codeLocation, Path.of(System.getProperty("user.home", ".")));
      }
      return Path.of(configuredDirectory).toAbsolutePath().normalize();
    }

    static Path defaultOutputRoot(Path codeLocation, Path userHome) {
      if (codeLocation != null && Files.isRegularFile(codeLocation)) {
        Path directory = codeLocation.toAbsolutePath().getParent().resolve("recordings");
        try {
          Files.createDirectories(directory);
          Path probe = Files.createTempFile(directory, ".write-test-", ".tmp");
          Files.delete(probe);
          return directory.normalize();
        } catch (IOException | SecurityException ignored) {
          // Keep recordings usable when the package was extracted into a protected directory.
        }
      }
      return userHome.resolve("TheLastHour").resolve("recordings").toAbsolutePath().normalize();
    }

    private static int integerProperty(String name, int defaultValue) {
      String value = System.getProperty(name);
      return value == null ? defaultValue : Integer.parseInt(value.trim());
    }

    static String sanitizeStudyId(String value) {
      if (value == null || value.isBlank()) {
        return "";
      }
      String sanitized = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
      return sanitized.substring(0, Math.min(48, sanitized.length()));
    }
  }

  record RawFrame(int width, int height, byte[] rgba) {
    RawFrame {
      Objects.requireNonNull(rgba, "rgba");
    }

    void validate() {
      if (width < 1 || height < 1) {
        throw new IllegalArgumentException("captured frame dimensions must be positive");
      }
      long expected = (long) width * height * 4L;
      if (expected > Integer.MAX_VALUE || rgba.length != (int) expected) {
        throw new IllegalArgumentException("captured RGBA buffer has the wrong length");
      }
    }
  }

  record MarkerFrame(String marker, String dialogId, SyncPoint point) {}

  private record MarkerRequest(String marker, String dialogId) {}

  record FrameJob(
      long captureIndex,
      long videoFrameIndex,
      long captureElapsedMillis,
      Instant capturedAt,
      RawFrame rawFrame,
      List<MarkerRequest> markers) {}

  interface FrameScaler {
    byte[] scale(RawFrame source, int targetWidth, int targetHeight);
  }

  interface VideoSinkFactory {
    VideoSink open(Config config, Path directory) throws IOException;
  }

  interface VideoSink extends AutoCloseable {
    void writeFrame(byte[] bgr) throws IOException;

    default void flush() throws IOException {}

    @Override
    void close() throws IOException;
  }

  static final class NearestNeighborBgrScaler implements FrameScaler {
    @Override
    public byte[] scale(RawFrame source, int targetWidth, int targetHeight) {
      source.validate();
      int[] scaled = scaledDimensions(source.width(), source.height(), targetWidth, targetHeight);
      int scaledWidth = scaled[0];
      int scaledHeight = scaled[1];
      int offsetX = (targetWidth - scaledWidth) / 2;
      int offsetY = (targetHeight - scaledHeight) / 2;
      byte[] output =
          new byte[Math.multiplyExact(Math.multiplyExact(targetWidth, targetHeight), 3)];

      for (int targetY = 0; targetY < scaledHeight; targetY++) {
        int sourceY = (int) ((long) targetY * source.height() / scaledHeight);
        int outputRow = ((targetY + offsetY) * targetWidth + offsetX) * 3;
        int sourceRow = sourceY * source.width() * 4;
        for (int targetX = 0; targetX < scaledWidth; targetX++) {
          int sourceX = (int) ((long) targetX * source.width() / scaledWidth);
          int sourceIndex = sourceRow + sourceX * 4;
          int outputIndex = outputRow + targetX * 3;
          output[outputIndex] = source.rgba()[sourceIndex + 2];
          output[outputIndex + 1] = source.rgba()[sourceIndex + 1];
          output[outputIndex + 2] = source.rgba()[sourceIndex];
        }
      }
      return output;
    }

    private static int[] scaledDimensions(
        int sourceWidth, int sourceHeight, int maximumWidth, int maximumHeight) {
      if ((long) sourceWidth * maximumHeight >= (long) sourceHeight * maximumWidth) {
        return new int[] {
          maximumWidth, Math.max(1, (int) ((long) sourceHeight * maximumWidth / sourceWidth))
        };
      }
      return new int[] {
        Math.max(1, (int) ((long) sourceWidth * maximumHeight / sourceHeight)), maximumHeight
      };
    }
  }

  static final class FfmpegVideoSink implements VideoSink {
    private static final long PROCESS_TIMEOUT_SECONDS = 60L;

    private final Config config;
    private final Process process;
    private final OutputStream input;
    private final Path partialVideo;
    private final Path finalVideo;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FfmpegVideoSink(
        Config config, Process process, OutputStream input, Path partialVideo, Path finalVideo) {
      this.config = config;
      this.process = process;
      this.input = input;
      this.partialVideo = partialVideo;
      this.finalVideo = finalVideo;
    }

    static FfmpegVideoSink open(Config config, Path directory) throws IOException {
      Path partialVideo = directory.resolve("recording.mkv.part");
      Path finalVideo = directory.resolve("recording.mkv");
      Path logFile = directory.resolve("ffmpeg.log");
      List<String> command = new ArrayList<>();
      command.add(config.ffmpeg().command());
      command.addAll(
          List.of(
              "-hide_banner",
              "-loglevel",
              "warning",
              "-nostdin",
              "-y",
              "-f",
              "rawvideo",
              "-pixel_format",
              "bgr24",
              "-video_size",
              config.width() + "x" + config.height(),
              "-framerate",
              Integer.toString(config.framesPerSecond()),
              "-i",
              "pipe:0",
              "-an",
              "-c:v",
              "libx264",
              "-preset",
              config.preset(),
              "-crf",
              Integer.toString(config.crf()),
              "-threads",
              Integer.toString(config.encoderThreads()),
              "-pix_fmt",
              "yuv420p",
              "-f",
              "matroska",
              partialVideo.toString()));

      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
              .start();
      if (!process.isAlive()) {
        throw new IOException("FFmpeg exited before accepting video frames");
      }
      return new FfmpegVideoSink(
          config,
          process,
          new BufferedOutputStream(process.getOutputStream(), 4 * 1_024 * 1_024),
          partialVideo,
          finalVideo);
    }

    @Override
    public void writeFrame(byte[] bgr) throws IOException {
      int expected = Math.multiplyExact(Math.multiplyExact(config.width(), config.height()), 3);
      if (bgr.length != expected) {
        throw new IOException("Scaled frame has " + bgr.length + " bytes; expected " + expected);
      }
      input.write(bgr);
    }

    @Override
    public void flush() throws IOException {
      input.flush();
    }

    @Override
    public void close() throws IOException {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      IOException closeFailure = null;
      try {
        input.close();
      } catch (IOException exception) {
        closeFailure = exception;
      }

      boolean finished;
      try {
        finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new IOException("Interrupted while finalizing Matroska recording", exception);
      }
      if (!finished) {
        process.destroyForcibly();
        throw new IOException("FFmpeg did not finalize the Matroska file within 60 seconds");
      }
      if (closeFailure != null) {
        throw closeFailure;
      }
      if (process.exitValue() != 0) {
        throw new IOException("FFmpeg exited with code " + process.exitValue());
      }
      if (!Files.isRegularFile(partialVideo) || Files.size(partialVideo) == 0L) {
        throw new IOException("FFmpeg did not create a Matroska video");
      }
      try {
        Files.move(
            partialVideo,
            finalVideo,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(partialVideo, finalVideo, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
