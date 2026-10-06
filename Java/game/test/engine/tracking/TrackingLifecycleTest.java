package engine.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.Game;
import engine.game.ServerLifecycle;
import engine.network.NetworkUtils;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import rooms.lasthour.recording.LastHourRunFinalizer;
import testingUtils.MockNetworkHandler;
import tracking.core.TrackingJson;
import tracking.core.TrackingSessionStatus;

class TrackingLifecycleTest {
  @TempDir Path outboxDirectory;

  @AfterEach
  void resetTrackingLifecycle() throws ReflectiveOperationException {
    setStaticField("session", null);
    setStaticField("sessionStartAttempted", false);
    setStaticField("persistenceFailure", null);
  }

  @Test
  void participantAssociationWaitsOnlyWhileMappingCanStillSucceed() throws Exception {
    resetTrackingLifecycle();
    assertTrue(Tracking.participantAssociationPending());

    TrackingSession session = session();
    setStaticField("session", session);
    setStaticField("sessionStartAttempted", true);
    assertTrue(Tracking.participantAssociationPending());

    Files.delete(session.outboxPath());
    assertTrue(
        Tracking.roomEvent("puzzle", "object", TrackingJson.object()).isEmpty(),
        "A failed append must be reported through the facade");
    assertFalse(Tracking.participantAssociationPending());
  }

  private TrackingSession session() {
    return new TrackingSession(
        new TrackingConfig(
            "room",
            URI.create("http://127.0.0.1:8088"),
            Optional.empty(),
            outboxDirectory,
            TrackingConfig.DEFAULT_OPERATOR_EMAIL));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void finalizationFixesTrackingOutcomeBeforeAnyLaterShutdown(boolean completed) throws Exception {
    resetTrackingLifecycle();
    TrackingSession session = session();
    setStaticField("session", session);
    MockNetworkHandler.useLocalNetworkHandler();
    Field run = LastHourRunFinalizer.class.getDeclaredField("serverRun");
    run.setAccessible(true);
    run.set(null, null);
    var previousApp = com.badlogic.gdx.Gdx.app;
    com.badlogic.gdx.Gdx.app = null;
    try (var network = Mockito.mockStatic(NetworkUtils.class)) {
      network.when(NetworkUtils::getServerSessions).thenReturn(Map.of());
      if (completed) LastHourRunFinalizer.completeGame();
      else LastHourRunFinalizer.requestOperatorStop();
      var expected = completed ? TrackingSessionStatus.COMPLETED : TrackingSessionStatus.ABORTED;
      assertEquals(
          expected,
          TrackingJson.readJsonlRecoveringTruncatedTail(session.outboxPath())
              .finish()
              .orElseThrow()
              .status());
      assertTrue(LastHourRunFinalizer.serverFinalizing(), "Server stays available for reconnects");
      Game.exit("window closed");
      var constructor = ServerLifecycle.class.getDeclaredConstructor(Runnable.class);
      constructor.setAccessible(true);
      ServerLifecycle lifecycle = constructor.newInstance((Runnable) () -> {});
      lifecycle.requestExit("Ctrl+C or headless shutdown");
      Game.complete();
      assertEquals(
          expected,
          TrackingJson.readJsonlRecoveringTruncatedTail(session.outboxPath())
              .finish()
              .orElseThrow()
              .status());
    } finally {
      run.set(null, null);
      com.badlogic.gdx.Gdx.app = previousApp;
    }
  }

  private static void setStaticField(String name, Object value)
      throws ReflectiveOperationException {
    Field field = Tracking.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }
}
