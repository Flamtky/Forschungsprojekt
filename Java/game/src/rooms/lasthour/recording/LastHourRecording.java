package rooms.lasthour.recording;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.LifecycleListener;
import engine.Game;
import engine.utils.logging.DungeonLogger;
import feature.hud.dialogs.DialogCallbackResolver;
import feature.hud.dialogs.DialogContext;
import feature.hud.dialogs.DialogContextKeys;
import feature.hud.dialogs.DialogFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import rooms.lasthour.recording.FfmpegVideoRecorder.Config;
import rooms.lasthour.recording.FfmpegVideoRecorder.FinalizationSnapshot;
import rooms.lasthour.recording.FfmpegVideoRecorder.MarkerFrame;
import rooms.lasthour.recording.FfmpegVideoRecorder.Status;

/** Client-side integration for the automatic Last Hour Matroska recording. */
public final class LastHourRecording {
  private static final DungeonLogger LOGGER = DungeonLogger.getLogger(LastHourRecording.class);
  private static final AtomicBoolean dialogListenerInstalled = new AtomicBoolean(false);
  private static final AtomicBoolean lifecycleListenerInstalled = new AtomicBoolean(false);
  private static final AtomicBoolean shutdownHookInstalled = new AtomicBoolean(false);
  private static final AtomicBoolean stopStarted = new AtomicBoolean(false);
  private static final FramebufferCapture framebufferCapture = new FramebufferCapture();

  private static volatile FfmpegVideoRecorder recorder;
  private static volatile String username = "";
  private static volatile String startupFailure = "";
  private static volatile boolean recordingDisabled;
  private static volatile boolean finalizing;
  private static volatile Status reportedStatus;
  private static volatile CompletableFuture<Finalization> finishFuture;
  private static String retainedRecordingId;
  private static CompletableFuture<Finalization> retainedFinishFuture;

  private LastHourRecording() {}

  /** Installs the marker listener before a transported intro dialog can be materialized. */
  public static void installDialogListener() {
    if (dialogListenerInstalled.compareAndSet(false, true)) {
      DialogFactory.addCreationListener(LastHourRecording::dialogCreated);
    }
  }

  /** Starts recording and registers capture after the complete frame has been rendered. */
  public static synchronized void start() {
    installDialogListener();
    if (!Boolean.parseBoolean(System.getProperty(FfmpegVideoRecorder.ENABLED_PROPERTY, "true"))) {
      recordingDisabled = true;
      startupFailure = "disabled by system property";
      updateWindowTitle();
      LOGGER.warn("The Last Hour recording is disabled.");
      return;
    }
    recordingDisabled = false;
    if (recorder != null) {
      return;
    }

    try {
      Config config = Config.fromSystemProperties();
      recorder = FfmpegVideoRecorder.start(config, LastHourRecording::markerEncoded);
      Game.userOnFrameRendered(LastHourRecording::captureRenderedFrame);
      installLifecycleListener();
      installShutdownHook();
      reportedStatus = Status.RUNNING;
      updateWindowTitle();
      LOGGER.info(
          "Recording {} at {} fps in {}",
          recorder.recordingId(),
          recorder.framesPerSecond(),
          recorder.directory());
    } catch (IOException | RuntimeException exception) {
      startupFailure =
          exception.getMessage() == null ? exception.toString() : exception.getMessage();
      updateWindowTitle();
      LOGGER.warn("Could not start The Last Hour recording: {}", startupFailure, exception);
    }
  }

  /**
   * Records the configured player name and updates the visible status in the window title.
   *
   * @param connectedUsername configured player name of this client
   */
  public static void connected(String connectedUsername) {
    username = connectedUsername == null ? "" : connectedUsername;
    FfmpegVideoRecorder active = recorder;
    if (active != null) {
      active.connected(username);
    }
    updateWindowTitle();
  }

  /** Captures at most one due frame. This method must run at the end of the render thread. */
  public static void captureRenderedFrame() {
    FfmpegVideoRecorder active = recorder;
    if (active == null) {
      return;
    }
    try {
      active.captureIfDue(() -> framebufferCapture.capture(Gdx.graphics, Gdx.gl));
    } catch (RuntimeException exception) {
      LOGGER.warn("Recording frame capture failed: {}", exception.getMessage(), exception);
    }
    if (reportedStatus != active.status()) {
      reportedStatus = active.status();
      updateWindowTitle();
      if (active.status() == Status.FAILED) {
        LOGGER.warn("The Last Hour recording failed: {}", active.failureMessage());
      }
    }
  }

  /** Flushes queued frames and writes the standalone local replay viewer. */
  public static synchronized void stop() {
    FfmpegVideoRecorder active = recorder;
    if (active == null || !stopStarted.compareAndSet(false, true)) {
      return;
    }
    active.close();
    reportedStatus = active.status();
    LOGGER.info(
        "Recording {} stopped with {} frames and {} dropped frames in {}",
        active.recordingId(),
        active.framesWritten(),
        active.framesDropped(),
        active.directory());
  }

  /**
   * Stops the recorder off the render thread and returns its validated final state.
   *
   * @return future completed after FFmpeg and all recording metadata have been finalized
   */
  public static synchronized CompletableFuture<Finalization> finishAsync() {
    if (finishFuture != null) {
      return finishFuture;
    }
    finalizing = true;
    updateWindowTitle();
    CompletableFuture<Finalization> result = new CompletableFuture<>();
    finishFuture = result;
    Thread worker =
        new Thread(
            () -> {
              try {
                stop();
                result.complete(finalization());
              } catch (RuntimeException exception) {
                result.complete(
                    Finalization.failed(
                        startupFailure.isBlank() ? exception.toString() : startupFailure));
              } finally {
                finalizing = false;
                updateWindowTitleOnRenderThread();
              }
            },
            "last-hour-recording-finalizer");
    worker.setDaemon(false);
    worker.start();
    return result;
  }

  /**
   * Stops the new recorder but finalizes only the recording selected by the server.
   *
   * @param recordingId frozen visible recording identity
   * @return validation result for that recording, never a replacement with the new recording
   */
  public static synchronized CompletableFuture<Finalization> finishAsync(String recordingId) {
    FfmpegVideoRecorder active = recorder;
    if (recordingId.isBlank()
        || (active != null && active.recordingId().toString().equals(recordingId))) {
      return finishAsync();
    }
    if (recordingId.equals(retainedRecordingId)) {
      return retainedFinishFuture;
    }
    retainedRecordingId = recordingId;
    retainedFinishFuture =
        finishAsync()
            .thenApplyAsync(
                ignored -> {
                  try {
                    return fromSnapshot(
                        StoredRecording.finalizeRecording(directoryFor(recordingId)));
                  } catch (IOException | RuntimeException exception) {
                    return Finalization.failed(
                        "Retained recording unavailable: " + exception.getMessage());
                  }
                });
    return retainedFinishFuture;
  }

  static Path directoryFor(String recordingId) throws IOException {
    return StoredRecording.find(Config.outputRootFromSystemProperties(), recordingId);
  }

  /**
   * Returns the active output directory for status and smoke-test reporting.
   *
   * @return the output directory, or an empty optional before recorder initialization
   */
  public static Optional<Path> outputDirectory() {
    return Optional.ofNullable(recorder).map(FfmpegVideoRecorder::directory);
  }

  private static Finalization finalization() {
    FfmpegVideoRecorder active = recorder;
    if (active == null) {
      String failure =
          recordingDisabled
              ? "Recording was disabled"
              : startupFailure.isBlank() ? "Recording did not start" : startupFailure;
      return Finalization.failed(failure);
    }
    FinalizationSnapshot snapshot = active.finalizationSnapshot();
    return fromSnapshot(snapshot);
  }

  private static Finalization fromSnapshot(FinalizationSnapshot snapshot) {
    String validationFailure = validationFailure(snapshot);
    return new Finalization(
        validationFailure.isBlank() ? Finalization.Status.COMPLETE : Finalization.Status.FAILED,
        snapshot.studyId(),
        snapshot.recordingId().toString(),
        snapshot.framesPerSecond(),
        snapshot.width(),
        snapshot.height(),
        snapshot.format(),
        snapshot.videoBytes(),
        snapshot.framesWritten(),
        snapshot.framesDuplicated(),
        snapshot.framesDropped(),
        snapshot.markersWritten(),
        snapshot.ffmpegPlatform(),
        snapshot.ffmpegSource(),
        validationFailure,
        snapshot.recoveredAfterCrash());
  }

  static String validationFailure(FinalizationSnapshot snapshot) {
    if (snapshot.status() != Status.COMPLETE) {
      return snapshot.failure().isBlank()
          ? "Recorder status is " + snapshot.status()
          : snapshot.failure();
    }
    if (!snapshot.failure().isBlank()) {
      return snapshot.failure();
    }
    if (snapshot.studyId().isBlank()) {
      return "Study ID is missing";
    }
    if (snapshot.width() != 1280 || snapshot.height() != 720) {
      return "Expected a 1280x720 recording";
    }
    if (snapshot.framesPerSecond() != 20 && snapshot.framesPerSecond() != 30) {
      return "Expected a 20 or 30 fps recording";
    }
    if (!LastHourRecordingProtocol.FORMAT.equals(snapshot.format())) {
      return "Unexpected recording format: " + snapshot.format();
    }
    if (!"bundled".equals(snapshot.ffmpegSource())) {
      return "Recording did not use the bundled FFmpeg executable";
    }
    if (!snapshot.finalVideoPresent() || snapshot.videoBytes() == 0L) {
      return "Final recording.mkv is missing or empty";
    }
    if (snapshot.partialVideoPresent()) {
      return "Temporary recording.mkv.part still exists";
    }
    if (snapshot.framesWritten() == 0L) {
      return "No video frame was written";
    }
    if (snapshot.markersWritten() != 1) {
      return "Expected exactly one recording marker but found " + snapshot.markersWritten();
    }
    long capturedFrames = snapshot.framesWritten() + snapshot.framesDropped();
    if (!snapshot.recoveredAfterCrash() && snapshot.framesDropped() * 100 >= capturedFrames) {
      return String.format(
          java.util.Locale.ROOT,
          "Recording dropped %d of %d frames (%.2f%%; limit is below 1%%)",
          snapshot.framesDropped(),
          capturedFrames,
          100.0 * snapshot.framesDropped() / capturedFrames);
    }
    return "";
  }

  private static void updateWindowTitleOnRenderThread() {
    if (Gdx.app == null) {
      updateWindowTitle();
      return;
    }
    Gdx.app.postRunnable(LastHourRecording::updateWindowTitle);
  }

  private static void dialogCreated(DialogContext context) {
    context
        .find(DialogContextKeys.RECORDING_MARKER, String.class)
        .ifPresent(
            marker -> {
              Runnable armMarker =
                  () -> {
                    FfmpegVideoRecorder active = recorder;
                    if (active != null) {
                      active.markNextFrame(marker, context.dialogId());
                    }
                  };
              if (Gdx.app == null) {
                armMarker.run();
              } else {
                // Dialog creation can occur after the current stage draw. Arming on the next
                // application iteration guarantees that the selected frame contains the dialog.
                Gdx.app.postRunnable(armMarker);
              }
            });
  }

  private static void markerEncoded(MarkerFrame markerFrame) {
    if (!LastHourRecordingProtocol.INTRO_MARKER.equals(markerFrame.marker())) {
      return;
    }
    Runnable acknowledgement =
        () -> {
          try {
            DialogCallbackResolver.createButtonCallback(
                    markerFrame.dialogId(), LastHourRecordingProtocol.VISIBLE_SYNC_CALLBACK)
                .accept(markerFrame.point().toPayload());
          } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not acknowledge encoded recording marker: {}",
                exception.getMessage(),
                exception);
          }
        };
    if (Gdx.app != null) {
      Gdx.app.postRunnable(acknowledgement);
    }
  }

  private static void installLifecycleListener() {
    if (Gdx.app == null || !lifecycleListenerInstalled.compareAndSet(false, true)) {
      return;
    }
    Gdx.app.addLifecycleListener(
        new LifecycleListener() {
          @Override
          public void pause() {}

          @Override
          public void resume() {}

          @Override
          public void dispose() {
            LastHourRecording.stop();
          }
        });
  }

  private static void installShutdownHook() {
    if (shutdownHookInstalled.compareAndSet(false, true)) {
      Runtime.getRuntime()
          .addShutdownHook(new Thread(LastHourRecording::stop, "last-hour-recording-shutdown"));
    }
  }

  private static void updateWindowTitle() {
    if (Gdx.graphics == null) {
      return;
    }
    FfmpegVideoRecorder active = recorder;
    String base = username.isBlank() ? "The Last Hour" : "TheLastHour Client - " + username;
    String suffix;
    if (finalizing) {
      suffix = " [REC SAVING]";
    } else if (active == null) {
      if (recordingDisabled) {
        suffix = " [REC OFF]";
      } else {
        suffix = startupFailure.isBlank() ? " [REC STARTING]" : " [REC ERROR]";
      }
    } else {
      suffix =
          switch (active.status()) {
            case RUNNING -> " [REC " + active.framesPerSecond() + " fps]";
            case COMPLETE -> " [REC SAVED]";
            case FAILED -> " [REC ERROR]";
          };
    }
    Game.windowTitle(base + suffix);
  }

  /**
   * Immutable client-side result sent to the server after recorder shutdown.
   *
   * @param status validated local recording status
   * @param studyId configured pseudonymous study identity
   * @param recordingId local recording identity
   * @param framesPerSecond configured video frame rate
   * @param width encoded video width
   * @param height encoded video height
   * @param format versioned recording container and codec profile
   * @param videoBytes size of the finalized MKV
   * @param framesWritten number of encoded video frames
   * @param framesDuplicated number of frames inserted to preserve timing
   * @param framesDropped number of source frames rejected by the bounded queue
   * @param markersWritten number of synchronized recording markers
   * @param ffmpegPlatform selected FFmpeg platform
   * @param ffmpegSource source used to resolve FFmpeg
   * @param failure validation or encoder failure, otherwise empty
   * @param recoveredAfterCrash whether recovery left dropped/duplicated counters unreliable
   */
  public record Finalization(
      Status status,
      String studyId,
      String recordingId,
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
      String failure,
      boolean recoveredAfterCrash) {

    /** Final state of the local recording after validation. */
    public enum Status {
      COMPLETE,
      FAILED
    }

    private static Finalization failed(String failure) {
      return new Finalization(
          Status.FAILED, "", "", 0, 0, 0, "", 0L, 0L, 0L, 0L, 0, "", "", failure, false);
    }
  }
}
