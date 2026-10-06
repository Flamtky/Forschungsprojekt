package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rooms.lasthour.recording.BundledFfmpeg.Platform;
import rooms.lasthour.recording.BundledFfmpeg.ResolvedFfmpeg;
import rooms.lasthour.recording.FfmpegVideoRecorder.Config;
import rooms.lasthour.recording.FfmpegVideoRecorder.FfmpegVideoSink;
import rooms.lasthour.recording.FfmpegVideoRecorder.MarkerFrame;
import rooms.lasthour.recording.FfmpegVideoRecorder.NearestNeighborBgrScaler;
import rooms.lasthour.recording.FfmpegVideoRecorder.RawFrame;
import rooms.lasthour.recording.FfmpegVideoRecorder.Status;
import rooms.lasthour.recording.FfmpegVideoRecorder.VideoSink;

/** Tests video timing, marker persistence, overload handling, and FFmpeg integration. */
class FfmpegVideoRecorderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void defaultsToRecordingsNextToJarAndFallsBackWhenUnavailable() throws Exception {
    Path jar = Files.createFile(temporaryDirectory.resolve("TheLastHour.jar"));
    Path home = temporaryDirectory.resolve("home");
    Path recordings = temporaryDirectory.resolve("recordings");
    assertEquals(recordings, Config.defaultOutputRoot(jar, home));
    assertTrue(Files.isDirectory(recordings));

    Files.delete(recordings);
    Files.createFile(recordings);
    Path fallback = home.resolve("TheLastHour/recordings");
    assertEquals(fallback, Config.defaultOutputRoot(jar, home));
    assertEquals(fallback, Config.defaultOutputRoot(null, home));
    assertEquals(fallback, Config.defaultOutputRoot(temporaryDirectory, home));
  }

  @Test
  void writesTimestampedCaptureIndexMarkerMetadataAndViewer() throws Exception {
    AtomicLong nanos = new AtomicLong(1_000_000_000L);
    long initialNanos = nanos.get();
    Instant initialTime = Instant.parse("2026-09-04T12:00:00Z");
    List<MarkerFrame> markers = new ArrayList<>();
    CollectingSink sink = new CollectingSink();
    Config config = config("P001", 20, 8, 8, 4);

    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config,
            nanos::get,
            () -> initialTime.plusNanos(nanos.get() - initialNanos),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> sink,
            markers::add);

    assertTrue(recorder.captureIfDue(() -> splitColorFrame(16, 16)));
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(25));
    assertFalse(recorder.captureIfDue(() -> splitColorFrame(16, 16)));

    recorder.markNextFrame(LastHourRecordingProtocol.INTRO_MARKER, "dialog-intro");
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(25));
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(16, 16)));
    recorder.connected("Client-A");
    recorder.close();

    assertEquals(Status.COMPLETE, recorder.status());
    assertEquals(2, recorder.framesWritten());
    assertEquals(0, recorder.framesDropped());
    assertEquals(2, sink.frames.size());
    assertEquals(1, markers.size());
    assertEquals(1, markers.getFirst().point().frameIndex());
    assertEquals(50, markers.getFirst().point().videoSyncMs());

    byte[] firstFrame = sink.frames.getFirst();
    assertEquals(0, Byte.toUnsignedInt(firstFrame[0]));
    assertEquals(0, Byte.toUnsignedInt(firstFrame[1]));
    assertEquals(255, Byte.toUnsignedInt(firstFrame[2]));
    int bottomPixel = (6 * 8 + 2) * 3;
    assertEquals(255, Byte.toUnsignedInt(firstFrame[bottomPixel]));
    assertEquals(0, Byte.toUnsignedInt(firstFrame[bottomPixel + 2]));

    String summary = Files.readString(recorder.directory().resolve("recording.json"));
    assertTrue(summary.contains("\"status\":\"COMPLETE\""));
    assertTrue(summary.contains("\"format\":\"matroska-h264-v1\""));
    assertTrue(summary.contains("\"studyId\":\"P001\""));
    assertTrue(summary.contains("\"username\":\"Client-A\""));
    assertEquals(2, Files.readAllLines(recorder.directory().resolve("frames.jsonl")).size());
    assertEquals(1, Files.readAllLines(recorder.directory().resolve("markers.jsonl")).size());
    String viewer = Files.readString(recorder.directory().resolve("viewer.html"));
    assertTrue(viewer.contains("recording.mkv"));
    assertTrue(viewer.contains(LastHourRecordingProtocol.INTRO_MARKER));
  }

  @Test
  void fillsSkippedCaptureSlotsToKeepVideoTimeAligned() throws Exception {
    AtomicLong nanos = new AtomicLong();
    CollectingSink sink = new CollectingSink();
    List<MarkerFrame> markers = new ArrayList<>();
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config("P002", 20, 4, 4, 8),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> sink,
            markers::add);

    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    recorder.markNextFrame(LastHourRecordingProtocol.INTRO_MARKER, "dialog-intro");
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(150));
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    recorder.close();

    assertEquals(4, sink.frames.size());
    assertEquals(4, recorder.framesWritten());
    assertEquals(1, markers.size());
    assertEquals(3, markers.getFirst().point().frameIndex());
    assertEquals(150, markers.getFirst().point().videoSyncMs());
    assertTrue(
        Files.readString(recorder.directory().resolve("recording.json"))
            .contains("\"framesDuplicated\":2"));
  }

  @Test
  void unavailableFramebufferDuringResizePreservesTimelineAndPendingMarker() throws Exception {
    AtomicLong nanos = new AtomicLong();
    CollectingSink sink = new CollectingSink();
    List<MarkerFrame> markers = new ArrayList<>();
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            new Config(
                temporaryDirectory,
                "P002",
                20,
                1280,
                720,
                23,
                "veryfast",
                2,
                8,
                new ResolvedFfmpeg("unused", Platform.WINDOWS_X86_64, "bundled", "test-version")),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, outputDirectory) -> {
              Files.write(outputDirectory.resolve("recording.mkv"), new byte[] {1});
              return sink;
            },
            markers::add);

    assertTrue(recorder.captureIfDue(() -> splitColorFrame(16, 16)));
    recorder.markNextFrame(LastHourRecordingProtocol.INTRO_MARKER, "dialog-intro");
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
    assertFalse(recorder.captureIfDue(() -> null));
    assertEquals(Status.RUNNING, recorder.status());
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(32, 18)));
    recorder.close();

    assertEquals(Status.COMPLETE, recorder.status());
    assertEquals(3, sink.frames.size());
    assertEquals(0, recorder.framesDropped());
    assertEquals(0, recorder.finalizationSnapshot().framesDropped());
    assertEquals("", LastHourRecording.validationFailure(recorder.finalizationSnapshot()));
    assertTrue(sink.frames.stream().allMatch(frame -> frame.length == 1280 * 720 * 3));
    assertEquals(2, markers.getFirst().point().frameIndex());
    assertEquals(100, markers.getFirst().point().videoSyncMs());
  }

  @Test
  void queueOverloadKeepsPendingMarkerForTheNextAcceptedFrame() throws Exception {
    AtomicLong nanos = new AtomicLong();
    CountDownLatch writerStarted = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    AtomicBoolean firstFrame = new AtomicBoolean(true);
    List<MarkerFrame> markers = new ArrayList<>();
    VideoSink blockingSink =
        new VideoSink() {
          @Override
          public void writeFrame(byte[] ignored) throws IOException {
            if (firstFrame.compareAndSet(true, false)) {
              writerStarted.countDown();
              try {
                if (!releaseWriter.await(2, TimeUnit.SECONDS)) {
                  throw new IOException("test writer was not released");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
              }
            }
          }

          @Override
          public void close() {}
        };
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config("P003", 20, 4, 4, 1),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> blockingSink,
            markers::add);

    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    assertTrue(writerStarted.await(2, TimeUnit.SECONDS));
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    recorder.markNextFrame(LastHourRecordingProtocol.INTRO_MARKER, "dialog-intro");
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
    assertFalse(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    assertEquals(1, recorder.framesDropped());

    releaseWriter.countDown();
    waitUntil(() -> recorder.framesWritten() >= 2);
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    recorder.close();

    assertEquals(Status.COMPLETE, recorder.status());
    assertEquals(1, markers.size());
    assertEquals(3, markers.getFirst().point().frameIndex());
    assertEquals(150, markers.getFirst().point().videoSyncMs());
  }

  @Test
  void shutdownRejectsACaptureThatWasAlreadyReadingTheFramebuffer() throws Exception {
    AtomicLong nanos = new AtomicLong();
    CountDownLatch captureStarted = new CountDownLatch(1);
    CountDownLatch releaseCapture = new CountDownLatch(1);
    AtomicBoolean accepted = new AtomicBoolean(true);
    CollectingSink sink = new CollectingSink();
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config("P004", 20, 4, 4, 2),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> sink,
            ignored -> {});

    Thread capture =
        new Thread(
            () ->
                accepted.set(
                    recorder.captureIfDue(
                        () -> {
                          captureStarted.countDown();
                          try {
                            if (!releaseCapture.await(2, TimeUnit.SECONDS)) {
                              throw new IllegalStateException("test capture was not released");
                            }
                          } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                          }
                          return splitColorFrame(4, 4);
                        })),
            "test-framebuffer-capture");
    capture.start();
    assertTrue(captureStarted.await(2, TimeUnit.SECONDS));

    recorder.close();
    releaseCapture.countDown();
    capture.join(2_000L);

    assertFalse(capture.isAlive());
    assertFalse(accepted.get());
    assertEquals(0, sink.frames.size());
  }

  @Test
  void failedFinalMetadataWriteMarksTheRecorderFailed() throws Exception {
    AtomicLong nanos = new AtomicLong();
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config("P005", 20, 4, 4, 2),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> new CollectingSink(),
            ignored -> {});
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    Files.delete(recorder.directory().resolve("recording.json"));
    Files.createDirectory(recorder.directory().resolve("recording.json"));

    recorder.close();

    assertEquals(Status.FAILED, recorder.status());
    assertTrue(recorder.failureMessage().contains("final recording metadata"));
  }

  @Test
  void missingFinalMetadataMakesACompletedRecordingUnusable() throws Exception {
    AtomicLong nanos = new AtomicLong();
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config("P006", 20, 4, 4, 2),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> new CollectingSink(),
            ignored -> {});
    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    recorder.close();
    Files.delete(recorder.directory().resolve("recording.json"));

    assertTrue(recorder.finalizationSnapshot().failure().contains("recording.json is missing"));
  }

  @Test
  void writerFailureMarksRecordingFailedWithoutThrowingIntoTheGameLoop() throws Exception {
    AtomicLong nanos = new AtomicLong();
    VideoSink failingSink =
        new VideoSink() {
          @Override
          public void writeFrame(byte[] ignored) throws IOException {
            throw new IOException("simulated FFmpeg failure");
          }

          @Override
          public void close() {}
        };
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config("P004", 20, 4, 4, 2),
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            (ignoredConfig, ignoredDirectory) -> failingSink,
            ignored -> {});

    assertTrue(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    waitUntil(() -> recorder.status() == Status.FAILED);
    assertFalse(recorder.captureIfDue(() -> splitColorFrame(4, 4)));
    recorder.close();

    assertEquals(Status.FAILED, recorder.status());
    assertTrue(recorder.failureMessage().contains("simulated FFmpeg failure"));
    assertTrue(
        Files.readString(recorder.directory().resolve("recording.json"))
            .contains("\"status\":\"FAILED\""));
  }

  @Test
  void rejectsUnsafeOrUnusableConfigurationValues() {
    Config sanitized = config("../P 005", 20, 1280, 720, 10);
    assertEquals(".._P_005", sanitized.studyId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Config(temporaryDirectory, "P005", 0, 1280, 720, 23, "veryfast", 2, 10, ffmpeg()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Config(temporaryDirectory, "P005", 20, 1279, 720, 23, "veryfast", 2, 10, ffmpeg()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Config(temporaryDirectory, "P005", 20, 1280, 720, 52, "veryfast", 2, 10, ffmpeg()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(temporaryDirectory, "P005", 20, 1280, 720, 23, "slow", 2, 10, ffmpeg()));
  }

  @Test
  void writesPlayableTwentyAndThirtyFpsMatroskaFilesWithTheRealBundledExecutable()
      throws Exception {
    String executable = System.getenv("LAST_HOUR_FFMPEG_TEST");
    Assumptions.assumeTrue(executable != null && !executable.isBlank());
    writeRealVideo(executable, 20);
    writeRealVideo(executable, 30);
  }

  private void writeRealVideo(String executable, int framesPerSecond) throws Exception {
    ResolvedFfmpeg realFfmpeg =
        new ResolvedFfmpeg(
            Path.of(executable).toAbsolutePath().toString(),
            Platform.current(),
            "integration-test",
            "integration-test");
    Config config =
        new Config(
            temporaryDirectory,
            "FFMPEG-" + framesPerSecond,
            framesPerSecond,
            64,
            64,
            23,
            "veryfast",
            2,
            60,
            realFfmpeg);
    AtomicLong nanos = new AtomicLong();
    FfmpegVideoRecorder recorder =
        FfmpegVideoRecorder.start(
            config,
            nanos::get,
            () -> Instant.parse("2026-09-04T12:00:00Z"),
            new NearestNeighborBgrScaler(),
            FfmpegVideoSink::open,
            ignored -> {});

    long frameIntervalNanos = TimeUnit.SECONDS.toNanos(1L) / framesPerSecond;
    for (int frame = 0; frame < framesPerSecond; frame++) {
      assertTrue(recorder.captureIfDue(() -> splitColorFrame(64, 64)));
      nanos.addAndGet(frameIntervalNanos);
    }
    recorder.close();

    Path video = recorder.directory().resolve("recording.mkv");
    assertEquals(Status.COMPLETE, recorder.status());
    assertTrue(Files.size(video) > 0L);
    Process validation =
        new ProcessBuilder(
                realFfmpeg.command(), "-v", "error", "-i", video.toString(), "-f", "null", "-")
            .start();
    assertTrue(validation.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, validation.exitValue());
  }

  private Config config(String studyId, int fps, int width, int height, int queueFrames) {
    return new Config(
        temporaryDirectory, studyId, fps, width, height, 23, "veryfast", 2, queueFrames, ffmpeg());
  }

  private static ResolvedFfmpeg ffmpeg() {
    return new ResolvedFfmpeg("unused", Platform.WINDOWS_X86_64, "test", "test-version");
  }

  private static RawFrame splitColorFrame(int width, int height) {
    byte[] pixels = new byte[width * height * 4];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int index = (y * width + x) * 4;
        pixels[index] = y < height / 2 ? (byte) 0xff : 0;
        pixels[index + 1] = 0;
        pixels[index + 2] = y < height / 2 ? 0 : (byte) 0xff;
        pixels[index + 3] = (byte) 0xff;
      }
    }
    return new RawFrame(width, height, pixels);
  }

  private static void waitUntil(SupplierWithException<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.get() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertTrue(condition.get(), "condition did not become true before timeout");
  }

  private static final class CollectingSink implements VideoSink {
    private final List<byte[]> frames = new ArrayList<>();

    @Override
    public void writeFrame(byte[] bgr) {
      frames.add(Arrays.copyOf(bgr, bgr.length));
    }

    @Override
    public void close() {}
  }

  @FunctionalInterface
  private interface SupplierWithException<T> {
    T get() throws Exception;
  }
}
