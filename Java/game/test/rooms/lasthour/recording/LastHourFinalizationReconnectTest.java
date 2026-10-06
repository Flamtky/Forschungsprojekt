package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyShort;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import engine.Game;
import engine.network.NetworkUtils;
import engine.network.handler.INetworkHandler;
import engine.network.messages.NetworkMessage;
import engine.network.messages.s2c.RecordingFinalizationComplete;
import engine.network.messages.s2c.RecordingFinalizationComplete.RunStatus;
import engine.network.messages.s2c.RecordingFinalizationRequest;
import engine.network.server.ClientState;
import engine.network.server.Session;
import engine.tracking.Tracking;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import rooms.lasthour.adaptation.LastHourAdaptation;
import testingUtils.MockNetworkHandler;
import tracking.core.ReplayProtocol;

class LastHourFinalizationReconnectTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void reconnectResendsFrozenRecordingDuringAndAfterFinalization(boolean replayReady)
      throws Exception {
    UUID recording = UUID.randomUUID();
    UUID completion = UUID.randomUUID();
    var state =
        new LastHourFinalizationState(
            completion,
            true,
            "done",
            2,
            90,
            Instant.now(),
            System.nanoTime(),
            List.of(
                new LastHourFinalizationState.ExpectedClient(
                    (short) 1, UUID.randomUUID().toString(), recording.toString())));
    state.accept(
        (short) 1,
        new engine.network.messages.c2s.RecordingFinalizationResult(
            completion,
            engine.network.messages.c2s.RecordingFinalizationResult.Status.COMPLETE,
            "P001",
            recording.toString(),
            20,
            1280,
            720,
            LastHourRecordingProtocol.FORMAT,
            10000,
            200,
            0,
            0,
            1,
            "windows-x86_64",
            "bundled",
            "",
            false));
    Class<?> runType = Class.forName(LastHourRunFinalizer.class.getName() + "$ServerRun");
    var constructor = runType.getDeclaredConstructor(LastHourFinalizationState.class, long.class);
    constructor.setAccessible(true);
    Object run = constructor.newInstance(state, Long.MAX_VALUE);
    String launchJson = "{\"frozenRecording\":\"" + recording + "\"}";
    if (replayReady) {
      field(runType, "finishStarted").set(run, true);
      field(runType, "completionNoticeFinished").set(run, true);
      @SuppressWarnings("unchecked")
      var completions =
          (Map<Short, RecordingFinalizationComplete>) field(runType, "completions").get(run);
      completions.put(
          (short) 1,
          new RecordingFinalizationComplete(completion, RunStatus.COMPLETE, "done", launchJson));
    }
    Field serverRun = LastHourRunFinalizer.class.getDeclaredField("serverRun");
    serverRun.setAccessible(true);
    serverRun.set(null, run);
    var network = mock(INetworkHandler.class);
    List<NetworkMessage> sent = new ArrayList<>();
    when(network.send(anyShort(), any(), eq(true)))
        .thenAnswer(
            call -> {
              sent.add(call.getArgument(1));
              return CompletableFuture.completedFuture(true);
            });
    MockNetworkHandler.useNetworkHandler(network);
    Session restarted = mock(Session.class);
    ClientState client = mock(ClientState.class);
    when(client.initialWorldReady()).thenReturn(true);
    when(restarted.clientState()).thenReturn(Optional.of(client));
    try (var sessions = mockStatic(NetworkUtils.class)) {
      sessions.when(NetworkUtils::getServerSessions).thenReturn(Map.of((short) 1, restarted));
      LastHourRunFinalizer.tickServer();
      assertEquals(1, sent.size());
      if (replayReady) {
        var complete = (RecordingFinalizationComplete) sent.getFirst();
        assertEquals(launchJson, complete.replayLaunchJson());
        assertEquals(completion, complete.completionId());
        assertTrue(LastHourRunFinalizer.serverFinalizing());
      } else {
        var request = (RecordingFinalizationRequest) sent.getFirst();
        assertEquals(recording.toString(), request.recordingId());
        assertEquals(completion, request.completionId());
      }
      LastHourRunFinalizer.tickServer();
      assertEquals(1, sent.size());
    } finally {
      serverRun.set(null, null);
      MockNetworkHandler.useLocalNetworkHandler();
    }
  }

  private static Field field(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  @Test
  void disconnectImmediatelyBeforeSnapshotKeepsParticipantAndLastVisibleRecording()
      throws Exception {
    UUID participant = UUID.randomUUID();
    UUID recording = UUID.randomUUID();
    var network = mock(INetworkHandler.class);
    when(network.send(anyShort(), any(), eq(true)))
        .thenReturn(CompletableFuture.completedFuture(true));
    MockNetworkHandler.useNetworkHandler(network);
    Session disconnected = mock(Session.class);
    when(disconnected.isClosed()).thenReturn(true);
    try (var sessions = mockStatic(NetworkUtils.class);
        var tracking = mockStatic(Tracking.class);
        var adaptation = mockStatic(LastHourAdaptation.class)) {
      sessions.when(NetworkUtils::getServerSessions).thenReturn(Map.of((short) 1, disconnected));
      tracking
          .when(() -> Tracking.retainedParticipantForClient((short) 1))
          .thenReturn(Optional.of(participant));
      adaptation
          .when(() -> LastHourAdaptation.visibleRecordingId(participant))
          .thenReturn(Optional.of(recording));
      LastHourRunFinalizer.completeGame();
      Object run = field(LastHourRunFinalizer.class, "serverRun").get(null);
      var state = (LastHourFinalizationState) field(run.getClass(), "state").get(run);
      assertEquals(participant.toString(), state.expectedClients().get((short) 1).participantId());
      assertEquals(
          recording.toString(), state.expectedClients().get((short) 1).visibleRecordingId());
      verify(network)
          .send(
              eq((short) 1),
              argThat(
                  message ->
                      message instanceof RecordingFinalizationRequest request
                          && request.recordingId().equals(recording.toString())),
              eq(true));
    } finally {
      field(LastHourRunFinalizer.class, "serverRun").set(null, null);
      MockNetworkHandler.useLocalNetworkHandler();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void readyReplayWithoutClientRunUsesOldDirectoryOrReportsMissingRecording(boolean readable)
      throws Exception {
    UUID recording = UUID.randomUUID();
    var plan =
        new ReplayProtocol.Plan(
            1,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "P001",
            recording.toString(),
            0,
            0,
            List.of(new ReplayProtocol.Clip("S1", 3, "E1", "", "HINT_OFFER", 1000, 0, 1000, 2000)));
    String json =
        ReplayProtocol.JSON.writeValueAsString(
            new ReplayProtocol.Launch(
                "http://localhost:8080",
                ReplayProtocol.sign(plan, "test-only-replay-signing-key-32-characters"),
                ReplayProtocol.hash("code")));
    Path oldDirectory = Path.of("old-recording");
    var finalization =
        new LastHourRecording.Finalization(
            readable
                ? LastHourRecording.Finalization.Status.COMPLETE
                : LastHourRecording.Finalization.Status.FAILED,
            "P001",
            recording.toString(),
            20,
            1280,
            720,
            LastHourRecordingProtocol.FORMAT,
            10000,
            200,
            0,
            0,
            1,
            "windows-x86_64",
            "bundled",
            readable ? "" : "unreadable",
            false);
    field(LastHourRunFinalizer.class, "clientRun").set(null, null);
    var previousApp = com.badlogic.gdx.Gdx.app;
    com.badlogic.gdx.Gdx.app = null;
    try (var recorder = mockStatic(LastHourRecording.class);
        var replay = mockStatic(LocalReplayMain.class);
        var game = mockStatic(Game.class)) {
      recorder
          .when(() -> LastHourRecording.finishAsync(recording.toString()))
          .thenReturn(CompletableFuture.completedFuture(finalization));
      recorder
          .when(() -> LastHourRecording.directoryFor(recording.toString()))
          .thenReturn(oldDirectory);
      var receive =
          LastHourRunFinalizer.class.getDeclaredMethod(
              "receiveFinalizationComplete", RecordingFinalizationComplete.class);
      receive.setAccessible(true);
      var complete =
          new RecordingFinalizationComplete(UUID.randomUUID(), RunStatus.COMPLETE, "done", json);
      receive.invoke(null, complete);
      receive.invoke(null, complete);
      recorder.verify(() -> LastHourRecording.finishAsync(recording.toString()), times(1));
      if (readable) replay.verify(() -> LocalReplayMain.launch(oldDirectory, json), times(1));
      else replay.verifyNoInteractions();
      game.verify(() -> Game.exit("done"), times(1));
    } finally {
      com.badlogic.gdx.Gdx.app = previousApp;
      field(LastHourRunFinalizer.class, "clientRun").set(null, null);
    }
  }
}
