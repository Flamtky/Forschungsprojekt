package rooms.lasthour.recording;

import com.badlogic.gdx.Gdx;
import engine.Game;
import engine.network.NetworkUtils;
import engine.network.messages.c2s.RecordingFinalizationResult;
import engine.network.messages.s2c.RecordingFinalizationComplete;
import engine.network.messages.s2c.RecordingFinalizationComplete.RunStatus;
import engine.network.messages.s2c.RecordingFinalizationRequest;
import engine.network.server.Session;
import engine.tracking.Tracking;
import engine.tracking.TrackingRuntime;
import engine.utils.logging.DungeonLogger;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import rooms.lasthour.adaptation.LastHourAdaptation;
import rooms.lasthour.recording.LastHourFinalizationState.Acceptance;
import rooms.lasthour.recording.LastHourFinalizationState.ExpectedClient;
import rooms.lasthour.recording.LastHourRecording.Finalization;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tracking.core.TrackingJson;

/** Coordinates recorder shutdown between the Last Hour server and all connected clients. */
public final class LastHourRunFinalizer {
  public static final String TIMEOUT_PROPERTY = "lasthour.finalization.timeoutSeconds";
  public static final String EXPECTED_CLIENTS_PROPERTY = "lasthour.finalization.expectedClients";

  private static final DungeonLogger LOGGER = DungeonLogger.getLogger(LastHourRunFinalizer.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Object LOCK = new Object();
  private static final int DEFAULT_TIMEOUT_SECONDS = 90;
  private static final int DEFAULT_EXPECTED_CLIENTS = 2;
  private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
  private static final AtomicBoolean serverInstalled = new AtomicBoolean();
  private static final AtomicBoolean clientInstalled = new AtomicBoolean();

  private static Consumer<String> serverStatusSink = ignored -> {};
  private static Runnable closeServerStatus = () -> {};
  private static ServerRun serverRun;
  private static ClientRun clientRun;

  private LastHourRunFinalizer() {}

  /**
   * Returns the configured team size shared by gameplay start and recording finalization.
   *
   * @return positive expected client count, defaulting to two
   */
  public static int expectedClients() {
    return positiveProperty(EXPECTED_CLIENTS_PROPERTY, DEFAULT_EXPECTED_CLIENTS);
  }

  /**
   * Registers the server result handler and status callbacks.
   *
   * @param statusSink displays finalization progress to the operator
   * @param closeStatus closes the standalone server status window
   */
  public static void installServer(Consumer<String> statusSink, Runnable closeStatus) {
    serverStatusSink = statusSink == null ? ignored -> {} : statusSink;
    closeServerStatus = closeStatus == null ? () -> {} : closeStatus;
    if (serverInstalled.compareAndSet(false, true)) {
      Game.network()
          .messageDispatcher()
          .registerHandler(RecordingFinalizationResult.class, LastHourRunFinalizer::receiveResult);
    }
  }

  /** Registers client handlers for finalization requests and the terminal server confirmation. */
  public static void installClient() {
    if (!clientInstalled.compareAndSet(false, true)) {
      return;
    }
    Game.network()
        .messageDispatcher()
        .registerHandler(
            RecordingFinalizationRequest.class,
            (ignored, request) -> receiveFinalizationRequest(request));
    Game.network()
        .messageDispatcher()
        .registerHandler(
            RecordingFinalizationComplete.class,
            (ignored, complete) -> receiveFinalizationComplete(complete));
  }

  /** Starts the automatic successful-game shutdown after the final level. */
  public static void completeGame() {
    beginServerFinalization(true, "All levels completed");
  }

  /**
   * Starts the same safe recorder shutdown when the operator ends a run early, e.g. because a team
   * gives up. The run stays ABORTED; valid recordings still receive the replay questionnaire.
   */
  public static void requestOperatorStop() {
    Runnable request =
        () -> {
          synchronized (LOCK) {
            if (serverRun != null && serverRun.completionNoticeFinished) {
              serverRun.exitRequested = true;
              exitServer(serverRun.state.gameplayCompleted(), serverRun.state.reason());
              return;
            }
          }
          beginServerFinalization(false, "Run stopped by operator");
        };
    if (Gdx.app == null) {
      request.run();
    } else {
      Gdx.app.postRunnable(request);
    }
  }

  /** Advances retries and reconnect delivery; the operator closes the server after recovery. */
  public static void tickServer() {
    synchronized (LOCK) {
      if (serverRun == null || serverRun.exitRequested) {
        return;
      }
      long now = System.nanoTime();
      resendToReconnectedClients();
      if (!serverRun.finishStarted) {
        if (serverRun.state.deadlineReached(now)) {
          finishServer(true);
        } else if (now >= serverRun.nextRetryNanos) {
          persistPendingTrackingResults();
          sendRequests(serverRun.state.missingClientIds());
          serverRun.nextRetryNanos = now + RETRY_NANOS;
          if (serverRun.state.allResultsReceived()
              && serverRun.state.allFinalizationEventsPersisted()) {
            finishServer(false);
          }
        }
      }
    }
  }

  private static void resendToReconnectedClients() {
    var sessions = NetworkUtils.getServerSessions();
    for (short clientId : serverRun.state.expectedClients().keySet()) {
      Session session = sessions.get(clientId);
      if (session == null
          || session.isClosed()
          || !session.clientState().map(state -> state.initialWorldReady()).orElse(false)
          || serverRun.notifiedSessions.get(clientId) == session) {
        continue;
      }
      serverRun.notifiedSessions.put(clientId, session);
      RecordingFinalizationComplete complete = serverRun.completions.get(clientId);
      if (complete != null) {
        Game.network().send(clientId, complete, true);
      } else {
        sendRequests(List.of(clientId));
      }
    }
  }

  /**
   * Returns whether the server has frozen gameplay while it waits for recordings.
   *
   * @return true between the finalization request and server exit
   */
  public static boolean serverFinalizing() {
    synchronized (LOCK) {
      return serverRun != null && !serverRun.exitRequested;
    }
  }

  private static void beginServerFinalization(boolean gameplayCompleted, String reason) {
    synchronized (LOCK) {
      if (serverRun != null) {
        return;
      }
      int timeoutSeconds = positiveProperty(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS);
      int requiredClients = expectedClients();
      List<ExpectedClient> clients = new ArrayList<>();
      for (short clientId : NetworkUtils.getServerSessions().keySet()) {
        var participant = Tracking.retainedParticipantForClient(clientId);
        String participantId = participant.map(UUID::toString).orElse("");
        String visibleRecordingId =
            participant
                .flatMap(LastHourAdaptation::visibleRecordingId)
                .map(UUID::toString)
                .orElse("");
        clients.add(new ExpectedClient(clientId, participantId, visibleRecordingId));
      }
      long now = System.nanoTime();
      LastHourFinalizationState state =
          new LastHourFinalizationState(
              UUID.randomUUID(),
              gameplayCompleted,
              reason,
              requiredClients,
              timeoutSeconds,
              Instant.now(),
              now,
              clients);
      serverRun = new ServerRun(state, now + RETRY_NANOS);
      updateServerStatus(state);
      if (clients.isEmpty()) {
        finishServer(false);
      } else {
        sendRequests(state.missingClientIds());
      }
    }
  }

  private static void sendRequests(List<Short> clientIds) {
    if (serverRun == null || serverRun.finishStarted) {
      return;
    }
    LastHourFinalizationState state = serverRun.state;
    clientIds.forEach(
        clientId ->
            Game.network()
                .send(
                    clientId,
                    new RecordingFinalizationRequest(
                        state.completionId(),
                        state.reason(),
                        state.timeoutSeconds(),
                        state.gameplayCompleted(),
                        state.expectedClients().get(clientId).visibleRecordingId()),
                    true));
  }

  private static void receiveResult(Session session, RecordingFinalizationResult result) {
    if (session == null) {
      return;
    }
    synchronized (LOCK) {
      if (serverRun == null || serverRun.finishStarted) {
        return;
      }
      short clientId = session.clientId();
      Acceptance acceptance = serverRun.state.accept(clientId, result);
      if (acceptance != Acceptance.ACCEPTED) {
        LOGGER.warn(
            "Ignored recording finalization result from client {}: {}", clientId, acceptance);
        return;
      }
      if (recordTrackingResult(clientId, result)) {
        serverRun.state.markFinalizationEventPersisted(clientId);
      }
      updateServerStatus(serverRun.state);
      if (serverRun.state.allResultsReceived()
          && serverRun.state.allFinalizationEventsPersisted()) {
        finishServer(false);
      }
    }
  }

  private static void persistPendingTrackingResults() {
    if (serverRun == null || serverRun.finishStarted) {
      return;
    }
    for (short clientId : serverRun.state.unpersistedResultClientIds()) {
      RecordingFinalizationResult result = serverRun.state.results().get(clientId);
      if (result != null && recordTrackingResult(clientId, result)) {
        serverRun.state.markFinalizationEventPersisted(clientId);
      }
    }
  }

  private static boolean recordTrackingResult(short clientId, RecordingFinalizationResult result) {
    ExpectedClient client = serverRun.state.expectedClients().get(clientId);
    if (client == null || client.participantId().isBlank()) {
      return false;
    }
    ObjectNode payload = TrackingJson.object();
    payload.put("completionId", result.completionId().toString());
    payload.put("recordingId", result.recordingId());
    payload.put("studyId", result.studyId());
    payload.put("status", result.status().name());
    payload.put("usableForReplay", result.usableForReplay());
    payload.put("format", result.format());
    payload.put("width", result.width());
    payload.put("height", result.height());
    payload.put("framesPerSecond", result.framesPerSecond());
    payload.put("videoBytes", result.videoBytes());
    payload.put("framesWritten", result.framesWritten());
    payload.put("framesDuplicated", result.framesDuplicated());
    payload.put("framesDropped", result.framesDropped());
    payload.put("recoveredAfterCrash", result.recoveredAfterCrash());
    payload.put("markersWritten", result.markersWritten());
    if (!result.failure().isBlank()) {
      payload.put("failure", result.failure());
    }
    return Tracking.roomEvent(
            "pilot-finalization",
            "pilot.recording_finalized",
            payload,
            UUID.fromString(client.participantId()))
        .isPresent();
  }

  private static void finishServer(boolean timedOut) {
    ServerRun run = serverRun;
    if (run == null || run.finishStarted) {
      return;
    }
    run.finishStarted = true;
    run.state.finish(timedOut, Instant.now());
    RunStatus status = run.state.runStatus();
    java.util.Map<Short, String> replayLaunches = new java.util.HashMap<>();
    java.util.Map<Short, String> replayErrors = new java.util.HashMap<>();
    String replayError = "";
    boolean replayEnabled =
        Boolean.parseBoolean(System.getProperty("lasthour.replay.enabled", "true"));
    if (run.state.recordingsValid() && replayEnabled) {
      try {
        replayLaunches =
            prepareReplays(
                run.state,
                Tracking.outboxPath().orElseThrow(),
                Tracking::replayLaunch,
                replayErrors);
      } catch (IOException | RuntimeException exception) {
        replayError = " Replay unavailable: " + exception.getMessage();
        LOGGER.error("Could not prepare local replay questionnaires", exception);
      }
    }
    if (!replayErrors.isEmpty()) {
      replayError = " Replay unavailable for client IDs: " + replayErrors.keySet();
    }
    if (replayEnabled && replayLaunches.isEmpty() && run.state.gameplayCompleted()) {
      status = RunStatus.INVALID;
    }
    try {
      run.summaryPath = writeSummary(run.state, status);
      var summary = TrackingJson.object(Files.readString(run.summaryPath));
      summary.put("replayStatus", replayStatus(replayLaunches, !replayError.isEmpty()));
      summary.put("replayError", replayError);
      for (var client : summary.get("clients")) {
        short clientId = (short) client.get("clientId").intValue();
        ObjectNode entry = (ObjectNode) client;
        entry.put(
            "replayStatus",
            replayLaunches.containsKey(clientId)
                ? "READY"
                : replayErrors.containsKey(clientId) ? "FAILED" : "NOT_STARTED");
        entry.put("replayError", replayErrors.getOrDefault(clientId, ""));
      }
      LocalReplayFiles.writeJson(run.summaryPath, summary);
    } catch (IOException exception) {
      LOGGER.error("Could not write pilot run summary", exception);
    }
    String message = statusMessage(status, run.summaryPath) + replayError;
    // Freeze the tracking outcome now; later window/JVM shutdown must not turn it into ABORTED.
    if (run.state.gameplayCompleted()) {
      TrackingRuntime.completed();
    } else {
      TrackingRuntime.abortAtCurrentPuzzle();
    }
    serverStatusSink.accept(message);
    if (status != RunStatus.COMPLETE) {
      LOGGER.warn(message);
    }
    java.util.List<java.util.concurrent.CompletableFuture<Boolean>> notices = new ArrayList<>();
    for (short clientId : run.state.expectedClients().keySet()) {
      var complete =
          new RecordingFinalizationComplete(
              run.state.completionId(), status, message, replayLaunches.getOrDefault(clientId, ""));
      run.completions.put(clientId, complete);
      notices.add(Game.network().send(clientId, complete, true));
    }
    java.util.concurrent.CompletableFuture.allOf(
            notices.toArray(java.util.concurrent.CompletableFuture[]::new))
        .thenApply(
            ignored -> notices.stream().allMatch(notice -> Boolean.TRUE.equals(notice.join())))
        .orTimeout(5L, TimeUnit.SECONDS)
        .whenComplete(
            (success, failure) -> {
              synchronized (LOCK) {
                run.completionNoticeFinished = true;
                serverStatusSink.accept(
                    message + " Server remains available for reconnects; close when finished.");
                if (failure != null || !Boolean.TRUE.equals(success)) {
                  LOGGER.warn("Could not deliver the final run status to every client");
                }
              }
            });
  }

  static String replayStatus(java.util.Map<Short, String> launches, boolean failed) {
    return !launches.isEmpty() ? "READY" : failed ? "FAILED" : "NOT_STARTED";
  }

  static java.util.Map<Short, String> prepareReplays(
      LastHourFinalizationState state,
      Path outbox,
      java.util.function.BiFunction<
              tracking.core.ReplayProtocol.Plan, String, tracking.core.ReplayProtocol.Launch>
          signer,
      java.util.Map<Short, String> errors)
      throws IOException {
    // A reconnect during finalization may write a new marker; freeze planning at game end.
    var events =
        TrackingJson.readJsonlRecoveringTruncatedTail(outbox).events().stream()
            .filter(event -> !event.occurredAt().isAfter(state.startedAt()))
            .toList();
    java.util.Map<Short, String> launches = new java.util.HashMap<>();
    var approvals = JSON.createArrayNode();
    for (var client : state.expectedClients().values()) {
      if (!state.recordingUsable(client.clientId())) {
        continue;
      }
      try {
        var result = state.results().get(client.clientId());
        var plan =
            ReplayPlanner.create(
                events,
                UUID.fromString(client.participantId()),
                result.studyId(),
                result.recordingId(),
                result.framesWritten() * 1000 / result.framesPerSecond());
        String code = UUID.randomUUID().toString().replace("-", "");
        var launch = signer.apply(plan, tracking.core.ReplayProtocol.hash(code));
        String json = tracking.core.ReplayProtocol.JSON.writeValueAsString(launch);
        launches.put(client.clientId(), json);
        var approval =
            approvals
                .addObject()
                .put("studyId", result.studyId())
                .put("recordingId", result.recordingId())
                .put("deletionCode", code);
        approval.set("launch", tracking.core.ReplayProtocol.JSON.valueToTree(launch));
      } catch (RuntimeException exception) {
        errors.put(
            client.clientId(),
            exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage());
        LOGGER.warn(
            "Could not prepare replay for client {}: {}",
            client.clientId(),
            exception.getMessage());
      }
    }
    LocalReplayFiles.writeJson(
        outbox.resolveSibling(outbox.getFileName() + ".replay-approvals.json"), approvals);
    return launches;
  }

  private static String statusMessage(RunStatus status, Path summaryPath) {
    String path = summaryPath == null ? "no summary file" : summaryPath.toString();
    return switch (status) {
      case COMPLETE -> "PILOT COMPLETE: usable participant recordings saved; " + path;
      case INVALID -> "PILOT INVALID: no usable participant replay; " + path;
      case ABORTED -> "PILOT ABORTED: recordings saved where possible; " + path;
    };
  }

  private static void updateServerStatus(LastHourFinalizationState state) {
    int complete = state.results().size();
    int total = state.expectedClients().size();
    serverStatusSink.accept("Saving client recordings: " + complete + "/" + total);
  }

  private static void receiveFinalizationRequest(RecordingFinalizationRequest request) {
    RecordingFinalizationResult resend = null;
    synchronized (LOCK) {
      if (clientRun != null) {
        if (!clientRun.completionId.equals(request.completionId())) {
          LOGGER.warn("Ignored a second recording finalization with a different completion ID");
          return;
        }
        resend = clientRun.result;
      } else {
        clientRun = new ClientRun(request.completionId());
        LastHourRecording.finishAsync(request.recordingId())
            .whenComplete(
                (finalization, failure) ->
                    completeClientFinalization(request, finalization, failure));
      }
    }
    if (resend != null) {
      sendResult(resend);
    }
  }

  private static void completeClientFinalization(
      RecordingFinalizationRequest request,
      Finalization finalization,
      Throwable finalizationFailure) {
    RecordingFinalizationResult result =
        finalizationFailure == null
            ? toNetworkResult(request.completionId(), finalization)
            : failedNetworkResult(request.completionId(), finalizationFailure.toString());
    synchronized (LOCK) {
      if (clientRun == null || !clientRun.completionId.equals(request.completionId())) {
        return;
      }
      clientRun.result = result;
    }
    sendResult(result);
  }

  private static RecordingFinalizationResult toNetworkResult(
      UUID completionId, Finalization finalization) {
    RecordingFinalizationResult.Status status =
        finalization.status() == Finalization.Status.COMPLETE
            ? RecordingFinalizationResult.Status.COMPLETE
            : RecordingFinalizationResult.Status.FAILED;
    return new RecordingFinalizationResult(
        completionId,
        status,
        finalization.studyId(),
        finalization.recordingId(),
        finalization.framesPerSecond(),
        finalization.width(),
        finalization.height(),
        finalization.format(),
        finalization.videoBytes(),
        finalization.framesWritten(),
        finalization.framesDuplicated(),
        finalization.framesDropped(),
        finalization.markersWritten(),
        finalization.ffmpegPlatform(),
        finalization.ffmpegSource(),
        finalization.failure(),
        finalization.recoveredAfterCrash());
  }

  private static RecordingFinalizationResult failedNetworkResult(
      UUID completionId, String failure) {
    return new RecordingFinalizationResult(
        completionId,
        RecordingFinalizationResult.Status.FAILED,
        "",
        "",
        0,
        0,
        0,
        "",
        0L,
        0L,
        0L,
        0L,
        0,
        "",
        "",
        failure,
        false);
  }

  private static void sendResult(RecordingFinalizationResult result) {
    Game.network()
        .send((short) 0, result, true)
        .thenAccept(
            success -> {
              if (!success) {
                LOGGER.warn("Could not send recording finalization result to the server");
              }
            });
  }

  private static void receiveFinalizationComplete(RecordingFinalizationComplete complete) {
    synchronized (LOCK) {
      if (clientRun == null) {
        clientRun = new ClientRun(complete.completionId());
      }
      if (!clientRun.completionId.equals(complete.completionId()) || clientRun.completing) {
        return;
      }
      clientRun.completing = true;
    }
    if (!complete.replayLaunchJson().isBlank()) {
      try {
        var launch =
            tracking.core.ReplayProtocol.JSON.readValue(
                complete.replayLaunchJson(), tracking.core.ReplayProtocol.Launch.class);
        String recordingId = tracking.core.ReplayProtocol.readPlan(launch.ticket()).recordingId();
        LastHourRecording.finishAsync(recordingId)
            .whenComplete(
                (recording, failure) -> {
                  try {
                    if (failure != null || recording.status() != Finalization.Status.COMPLETE) {
                      throw new IOException("Old recording unavailable", failure);
                    }
                    LocalReplayMain.launch(
                        LastHourRecording.directoryFor(recordingId), complete.replayLaunchJson());
                  } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Replay could not open for the retained recording.", exception);
                  }
                  exitClient(complete.message());
                });
        return;
      } catch (RuntimeException exception) {
        LOGGER.error(
            "Replay could not open. Restart it from the local recording folder.", exception);
      }
    }
    exitClient(complete.message());
  }

  private static void exitClient(String message) {
    Runnable exit = () -> Game.exit(message);
    if (Gdx.app == null) {
      exit.run();
    } else {
      Gdx.app.postRunnable(exit);
    }
  }

  private static void exitServer(boolean gameplayCompleted, String reason) {
    closeServerStatus.run();
    if (gameplayCompleted) {
      Game.complete();
    } else {
      Game.exit(reason);
    }
  }

  private static Path writeSummary(LastHourFinalizationState state, RunStatus status)
      throws IOException {
    Path trackingPath =
        Tracking.outboxPath()
            .orElseGet(
                () -> Path.of("tracking-outbox", state.completionId() + ".jsonl").toAbsolutePath());
    return writeSummary(state, status, trackingPath);
  }

  static Path writeSummary(LastHourFinalizationState state, RunStatus status, Path trackingPath)
      throws IOException {
    String trackingName = trackingPath.getFileName().toString();
    String baseName =
        trackingName.endsWith(".jsonl")
            ? trackingName.substring(0, trackingName.length() - ".jsonl".length())
            : trackingName;
    Path target =
        trackingPath.resolveSibling(baseName + ".pilot-run-summary.json").toAbsolutePath();
    Files.createDirectories(target.getParent());

    ObjectNode root = JSON.createObjectNode();
    root.put("schemaVersion", 2);
    root.put("completionId", state.completionId().toString());
    root.put("runStatus", status.name());
    root.put("gameplayCompleted", state.gameplayCompleted());
    root.put("reason", state.reason());
    root.put("startedAt", state.startedAt().toString());
    root.put("finalizedAt", state.finalizedAt().toString());
    root.put("timeoutSeconds", state.timeoutSeconds());
    root.put("timedOut", state.timedOut());
    root.put("requiredClientCount", state.requiredClientCount());
    root.put("connectedClientCount", state.expectedClients().size());
    root.put("recordingResultsReceived", state.results().size());
    root.put("trackingOutbox", trackingPath.toString());
    ArrayNode clients = root.putArray("clients");
    for (ExpectedClient expected : state.expectedClients().values()) {
      ObjectNode client = clients.addObject();
      client.put("clientId", expected.clientId());
      if (!expected.participantId().isBlank()) {
        client.put("participantId", expected.participantId());
      }
      if (!expected.visibleRecordingId().isBlank()) {
        client.put("visibleSyncRecordingId", expected.visibleRecordingId());
      }
      client.put(
          "finalizationEventPersisted", state.finalizationEventPersisted(expected.clientId()));
      RecordingFinalizationResult result = state.results().get(expected.clientId());
      if (result == null) {
        client.put("status", state.timedOut() ? "TIMEOUT" : "MISSING");
        client.put("usableForReplay", false);
        continue;
      }
      client.put("status", result.status().name());
      client.put("usableForReplay", state.recordingUsable(expected.clientId()));
      client.put("studyId", result.studyId());
      client.put("recordingId", result.recordingId());
      client.put(
          "visibleSyncMatched",
          expected.visibleRecordingId().equalsIgnoreCase(result.recordingId().trim()));
      client.put("format", result.format());
      client.put("width", result.width());
      client.put("height", result.height());
      client.put("framesPerSecond", result.framesPerSecond());
      client.put("videoBytes", result.videoBytes());
      client.put("framesWritten", result.framesWritten());
      client.put("framesDuplicated", result.framesDuplicated());
      client.put("framesDropped", result.framesDropped());
      client.put("recoveredAfterCrash", result.recoveredAfterCrash());
      client.put("markersWritten", result.markersWritten());
      client.put("ffmpegPlatform", result.ffmpegPlatform());
      client.put("ffmpegSource", result.ffmpegSource());
      if (!result.failure().isBlank()) {
        client.put("failure", result.failure());
      }
    }
    writeAtomically(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    return target;
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        content,
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

  private static int positiveProperty(String name, int fallback) {
    String value = System.getProperty(name, "").trim();
    if (value.isEmpty()) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }

  private static final class ServerRun {
    private final LastHourFinalizationState state;
    private long nextRetryNanos;
    private boolean finishStarted;
    private boolean completionNoticeFinished;
    private final java.util.Map<Short, Session> notifiedSessions = new java.util.HashMap<>();
    private final java.util.Map<Short, RecordingFinalizationComplete> completions =
        new java.util.HashMap<>();
    private boolean exitRequested;
    private Path summaryPath;

    private ServerRun(LastHourFinalizationState state, long nextRetryNanos) {
      this.state = state;
      this.nextRetryNanos = nextRetryNanos;
    }
  }

  private static final class ClientRun {
    private final UUID completionId;
    private RecordingFinalizationResult result;
    private boolean completing;

    private ClientRun(UUID completionId) {
      this.completionId = completionId;
    }
  }
}
