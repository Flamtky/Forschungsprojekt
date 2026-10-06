package engine.network.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import engine.Entity;
import engine.Game;
import engine.game.PreRunConfiguration;
import engine.network.handler.NettyNetworkHandler;
import engine.network.messages.s2c.DialogShowMessage;
import feature.components.UIComponent;
import feature.entities.CharacterClass;
import feature.hud.dialogs.DialogContext;
import feature.hud.dialogs.DialogContextKeys;
import feature.hud.dialogs.DialogType;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testingUtils.MockNetworkHandler;

class DialogTrackerRecordingReconnectTest {
  private final short clientId = 1;
  private final DialogTracker tracker = DialogTracker.instance();
  private NettyNetworkHandler network;
  private ServerTransport transport;
  private Entity player;
  private Session original;

  @BeforeEach
  void setUp() {
    Game.removeAllEntities();
    Game.removeAllSystems();
    network = mock(NettyNetworkHandler.class);
    ServerRuntime runtime = mock(ServerRuntime.class);
    transport = mock(ServerTransport.class);
    when(network.isServer()).thenReturn(true);
    when(network.serverRuntime()).thenReturn(Optional.of(runtime));
    when(runtime.transport()).thenReturn(Optional.of(transport));
    MockNetworkHandler.useNetworkHandler(network);
    PreRunConfiguration.multiplayerEnabled(true);
    PreRunConfiguration.isNetworkServer(true);
    player = new Entity("rejoining-player");
    Game.add(player);
    ClientState state =
        new ClientState(clientId, "Player", 1, new byte[] {1}, CharacterClass.values()[0]);
    state.playerEntity(player);
    original = new Session(null, null, null);
    original.attachClientState(state);
    when(transport.clientIdToSessionMap()).thenReturn(Map.of(clientId, original));
  }

  @AfterEach
  void tearDown() {
    tracker.clear();
    Game.removeAllEntities();
    Game.removeAllSystems();
    PreRunConfiguration.multiplayerEnabled(false);
    PreRunConfiguration.isNetworkServer(true);
    MockNetworkHandler.useLocalNetworkHandler();
  }

  @Test
  void restartDuringIntroDiscardsTheOldMarkerAndItsPausingUi() {
    UIComponent intro = registerDialog(true);
    Session restarted = new Session(null, null, null);
    restarted.attachClientState(original.clientState().orElseThrow());
    when(transport.clientIdToSessionMap()).thenReturn(Map.of(clientId, restarted));
    clearInvocations(network);

    tracker.resyncDialogsToClient(clientId);

    verify(network, never()).send(eq(clientId), any(DialogShowMessage.class), eq(true));
    assertFalse(player.fetch(UIComponent.class).isPresent());
    assertFalse(tracker.canRespond(clientId, intro.dialogContext().dialogId()));
  }

  @Test
  void ordinaryWorldResyncKeepsTheIntroOnTheSameConnection() {
    UIComponent intro = registerDialog(true);

    tracker.resyncDialogsToClient(clientId);

    verify(network).send(eq(clientId), any(DialogShowMessage.class), eq(true));
    assertTrue(player.fetch(UIComponent.class).isPresent());
    assertTrue(tracker.canRespond(clientId, intro.dialogContext().dialogId()));
  }

  @Test
  void restartStillRestoresOrdinaryPuzzleDialogs() {
    UIComponent puzzle = registerDialog(false);
    Session restarted = new Session(null, null, null);
    restarted.attachClientState(original.clientState().orElseThrow());
    when(transport.clientIdToSessionMap()).thenReturn(Map.of(clientId, restarted));

    tracker.resyncDialogsToClient(clientId);

    verify(network).send(eq(clientId), any(DialogShowMessage.class), eq(true));
    assertTrue(player.fetch(UIComponent.class).isPresent());
    assertTrue(tracker.canRespond(clientId, puzzle.dialogContext().dialogId()));
  }

  private UIComponent registerDialog(boolean recordingMarker) {
    DialogContext context =
        new DialogContext(
            DialogType.DefaultTypes.OK,
            true,
            recordingMarker
                ? Map.of(DialogContextKeys.RECORDING_MARKER, "pilot.recording_sync")
                : Map.of());
    context.owner(player.id());
    UIComponent ui = new UIComponent(context, true, player.id());
    player.add(ui);
    tracker.registerDialog(ui);
    return ui;
  }
}
