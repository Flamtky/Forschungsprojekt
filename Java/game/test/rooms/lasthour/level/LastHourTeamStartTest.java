package rooms.lasthour.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import engine.Entity;
import engine.Game;
import engine.level.utils.DesignLabel;
import engine.level.utils.LevelElement;
import engine.network.handler.NettyNetworkHandler;
import engine.network.input.InputCommandRouter;
import engine.network.messages.c2s.InputMessage;
import engine.network.server.ClientState;
import engine.network.server.ServerRuntime;
import engine.network.server.ServerTransport;
import engine.network.server.Session;
import engine.tracking.Tracking;
import engine.utils.Point;
import engine.utils.Vector2;
import feature.systems.EventScheduler;
import feature.timer.WorldTimerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import rooms.lasthour.adaptation.LastHourAdaptation;
import rooms.lasthour.recording.LastHourRunFinalizer;
import rooms.lasthour.util.LastHourPuzzle;

class LastHourTeamStartTest {
  @Test
  void waitsForLiveTeamThenStartsOnceDespiteRetainedSeatsAndClientRestart() {
    try (var game = mockStatic(Game.class);
        var tracking = mockStatic(Tracking.class);
        var adaptation = mockStatic(LastHourAdaptation.class);
        var timers = mockStatic(WorldTimerFactory.class);
        var scheduler = mockStatic(EventScheduler.class);
        var finalizer = mockStatic(LastHourRunFinalizer.class)) {
      finalizer.when(LastHourRunFinalizer::expectedClients).thenReturn(2);
      Entity first = new Entity("first");
      Entity second = new Entity("second");
      var sessions = new HashMap<Short, Session>();
      Session firstConnection = connection(first, true);
      Session secondConnection = connection(second, false);
      sessions.put((short) 1, firstConnection);
      sessions.put((short) 2, secondConnection);
      NettyNetworkHandler network = mock(NettyNetworkHandler.class);
      ServerRuntime runtime = mock(ServerRuntime.class);
      ServerTransport transport = mock(ServerTransport.class);
      when(network.serverRuntime()).thenReturn(Optional.of(runtime));
      when(runtime.transport()).thenReturn(Optional.of(transport));
      when(transport.clientIdToSessionMap()).thenAnswer(ignored -> Map.copyOf(sessions));
      game.when(Game::network).thenReturn(network);
      game.when(Game::allPlayers).thenAnswer(ignored -> Stream.of(first, second));
      tracking
          .when(() -> Tracking.participantForEntity(anyInt()))
          .thenReturn(Optional.of(UUID.randomUUID()));
      timers
          .when(() -> WorldTimerFactory.createWorldTimer(any(), anyInt(), anyInt()))
          .thenReturn(new Entity("timer"));

      LastHourLevel level =
          new LastHourLevel(
              new LevelElement[][] {{LevelElement.FLOOR}},
              DesignLabel.DEFAULT,
              Map.of("timer", new Point(0, 0)));
      game.when(Game::currentLevel).thenReturn(Optional.of(level));
      AtomicInteger commands = new AtomicInteger();
      try {
        InputCommandRouter.register("test:puzzle", true, ignored -> commands.incrementAndGet());
        level.checkTeamStart();
        assertFalse(level.allowsPlayerInput());
        assertFalse(level.allowsDialogResponse("puzzle-dialog"));
        ClientState state = firstConnection.clientState().orElseThrow();
        assertFalse(
            InputCommandRouter.dispatch(
                state,
                first,
                new InputMessage(
                    1,
                    1,
                    (short) 1,
                    InputMessage.Action.MOVE,
                    new InputMessage.Move(Vector2.of(1, 0))),
                false));
        assertFalse(
            InputCommandRouter.dispatch(
                state,
                first,
                new InputMessage(
                    1,
                    1,
                    (short) 2,
                    InputMessage.Action.INTERACT,
                    new InputMessage.Interact(new Point(0, 0))),
                false));
        assertFalse(
            InputCommandRouter.dispatch(state, first, InputMessage.custom("test:puzzle"), false));
        assertEquals(0, commands.get());
        timers.verifyNoInteractions();
        adaptation.verifyNoInteractions();
        tracking.verify(() -> Tracking.puzzleStarted(anyString()), never());

        // A closed retained seat is not a second connected player, even if its world was ready.
        when(secondConnection.clientState().orElseThrow().initialWorldReady()).thenReturn(true);
        when(secondConnection.isClosed()).thenReturn(true);
        level.checkTeamStart();
        assertFalse(level.allowsPlayerInput());
        when(secondConnection.isClosed()).thenReturn(false);
        int beforeStart = (int) (System.currentTimeMillis() / 1000L);
        level.checkTeamStart();
        int afterStart = (int) (System.currentTimeMillis() / 1000L);
        assertTrue(level.allowsPlayerInput());
        assertTrue(level.allowsDialogResponse("puzzle-dialog"));
        assertTrue(
            InputCommandRouter.dispatch(state, first, InputMessage.custom("test:puzzle"), false));

        when(firstConnection.isClosed()).thenReturn(true);
        when(secondConnection.isClosed()).thenReturn(true);
        level.checkTeamStart();
        assertTrue(level.allowsPlayerInput());
        sessions.put((short) 1, connection(first, false));
        level.checkTeamStart();
        assertTrue(level.allowsPlayerInput());
        when(sessions.get((short) 1).clientState().orElseThrow().initialWorldReady())
            .thenReturn(true);
        level.checkTeamStart();
        assertTrue(level.allowsPlayerInput());

        timers.verify(
            () ->
                WorldTimerFactory.createWorldTimer(
                    eq(new Point(0, 0)),
                    intThat(timestamp -> timestamp >= beforeStart && timestamp <= afterStart),
                    eq(3600)),
            times(1));
        adaptation.verify(() -> LastHourAdaptation.started(LastHourPuzzle.POWER), never());
        tracking.verify(() -> Tracking.puzzleStarted(LastHourPuzzle.POWER.id()), never());
        tracking.verify(
            () ->
                Tracking.roomEvent(
                    eq(LastHourPuzzle.POWER.id()),
                    eq("pilot.team_ready"),
                    argThat(payload -> payload.path("participantCount").asInt() == 2)),
            times(1));
      } finally {
        InputCommandRouter.unregister("test:puzzle");
      }
    }
  }

  @Test
  void singleplayerStartsWithOnePlayerAndDefaultTeamSize() {
    String previous = System.getProperty(LastHourRunFinalizer.EXPECTED_CLIENTS_PROPERTY);
    System.clearProperty(LastHourRunFinalizer.EXPECTED_CLIENTS_PROPERTY);
    try (var game = mockStatic(Game.class);
        var tracking = mockStatic(Tracking.class);
        var adaptation = mockStatic(LastHourAdaptation.class);
        var timers = mockStatic(WorldTimerFactory.class);
        var scheduler = mockStatic(EventScheduler.class)) {
      game.when(Game::isSingleplayer).thenReturn(true);
      assertEquals(2, LastHourRunFinalizer.expectedClients());
      game.when(Game::allPlayers).thenAnswer(ignored -> Stream.of(new Entity("solo")));
      timers
          .when(() -> WorldTimerFactory.createWorldTimer(any(), anyInt(), anyInt()))
          .thenReturn(new Entity("timer"));
      LastHourLevel level =
          new LastHourLevel(
              new LevelElement[][] {{LevelElement.FLOOR}},
              DesignLabel.DEFAULT,
              Map.of("timer", new Point(0, 0)));
      level.checkTeamStart();
      assertTrue(level.allowsPlayerInput());
      adaptation.verify(() -> LastHourAdaptation.started(LastHourPuzzle.POWER), never());
      tracking.verify(() -> Tracking.puzzleStarted(LastHourPuzzle.POWER.id()), never());
    } finally {
      if (previous == null) System.clearProperty(LastHourRunFinalizer.EXPECTED_CLIENTS_PROPERTY);
      else System.setProperty(LastHourRunFinalizer.EXPECTED_CLIENTS_PROPERTY, previous);
    }
  }

  private static Session connection(Entity player, boolean ready) {
    Session connection = mock(Session.class);
    ClientState state = mock(ClientState.class);
    when(connection.clientState()).thenReturn(Optional.of(state));
    when(state.playerEntity()).thenReturn(Optional.of(player));
    when(state.initialWorldReady()).thenReturn(ready);
    return connection;
  }
}
