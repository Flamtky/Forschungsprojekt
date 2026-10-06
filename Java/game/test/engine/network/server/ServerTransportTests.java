package engine.network.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.Entity;
import engine.Game;
import engine.components.PlayerComponent;
import engine.game.PreRunConfiguration;
import engine.network.client.ClientNetwork;
import engine.network.codec.converters.s2c.ConnectAckConverter;
import engine.network.config.NetworkConfig;
import engine.network.messages.NetworkMessage;
import engine.network.messages.c2s.ConnectRequest;
import engine.network.messages.c2s.DebugPing;
import engine.network.messages.c2s.DebugTelemetryRequest;
import engine.network.messages.c2s.InputMessage;
import engine.network.messages.c2s.RegisterUdp;
import engine.network.messages.c2s.SnapshotAck;
import engine.network.messages.s2c.ConnectAck;
import engine.network.messages.s2c.ConnectReject;
import engine.network.messages.s2c.DebugPong;
import engine.network.messages.s2c.DebugTelemetrySnapshot;
import engine.network.messages.s2c.EntitySpawnEvent;
import engine.utils.Vector2;
import feature.entities.CharacterClass;
import feature.entities.HeroBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import rooms.lasthour.starter.LastHourClient;
import testingUtils.MockNetworkHandler;

/**
 * Unit tests for {@link ServerTransport}.
 *
 * <p>Validates Netty-based server transport initialization, channel lifecycle, session management,
 * message broadcasting, and input queue handling.
 */
public class ServerTransportTests {

  private static final int TEST_PORT = 17777;
  private static int portCounter = 0;
  private static final List<ServerTransport> transports = new ArrayList<>();
  private static final ThreadLocal<ServerTransport> currentTransport = new ThreadLocal<>();

  /**
   * Generates a unique port starting from the base TEST_PORT. Each call increments the port number
   * to ensure no conflicts between concurrent or sequential tests.
   *
   * @return a unique port number for testing
   */
  private static synchronized int uniquePort() {
    return TEST_PORT + (portCounter++);
  }

  /**
   * Stops all transports in the transports list and clears the list. This can be called to perform
   * batch cleanup of all created transports across all tests.
   */
  private static synchronized void stopAllTransports() {
    for (ServerTransport transport : transports) {
      if (transport != null) {
        try {
          transport.stop();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
    transports.clear();
  }

  /**
   * Sets up the server transport for testing by initializing {@link Game} with multiplayer server
   * configuration.
   *
   * <p>Configures the game to run in server mode and initializes a fresh {@link ServerTransport}
   * instance.
   */
  @BeforeEach
  public void setup() {
    ServerTransport transport = new ServerTransport();
    transports.add(transport);
    currentTransport.set(transport);
    PreRunConfiguration.multiplayerCharacterClasses(CharacterClass.WIZARD);
    MockNetworkHandler.useLocalNetworkHandler();
  }

  /**
   * Cleans up server transport resources and game state after each test.
   *
   * <p>Stops the transport, resets multiplayer configuration, and exits the game to ensure
   * isolation between tests.
   */
  @AfterEach
  public void cleanup() {
    ServerTransport transport = currentTransport.get();
    if (transport != null) {
      transport.stop();
      transports.remove(transport);
      currentTransport.remove();
    }
    PreRunConfiguration.multiplayerCharacterClasses(CharacterClass.WIZARD);
    NetworkConfig.DEBUG_TELEMETRY_ENABLED = false;
  }

  /**
   * Validates that the transport starts successfully on the configured test port and creates both
   * TCP and UDP channels.
   */
  @Test
  public void test_transportStartsOnConfiguredPort() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();
    transport.start(port);
    assertNotNull(transport.tcpServerChannel());
    assertNotNull(transport.udpChannel());
  }

  /** Validates that both TCP and UDP channels are active after the transport starts. */
  @Test
  public void test_transportChannelsActive() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();

    transport.start(port);
    assertTrue(transport.tcpServerChannel().isActive());
    assertTrue(transport.udpChannel().isActive());
  }

  /** Validates that both TCP and UDP channels are properly closed when the transport stops. */
  @Test
  public void test_stopClosesChannels() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();
    transport.start(port);
    assertTrue(transport.tcpServerChannel().isActive());
    assertTrue(transport.udpChannel().isActive());

    transport.stop();
    assertFalse(transport.tcpServerChannel().isActive());
    assertFalse(transport.udpChannel().isActive());
  }

  /** Validates that the session map is empty after transport initialization. */
  @Test
  public void test_sessionsMapEmpty() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();
    transport.start(port);
    assertTrue(transport.sessions().isEmpty());
  }

  /** Validates that the client ID to session mapping is empty after transport initialization. */
  @Test
  public void test_clientIdMappingEmpty() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();
    transport.start(port);
    assertTrue(transport.clientIdToSessionMap().isEmpty());
  }

  /**
   * Validates that broadcasting a message to an empty session map returns a completed {@link
   * CompletableFuture}.
   */
  @Test
  public void test_broadcastEmptySessionMap() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();
    transport.start(port);
    NetworkMessage msg = Mockito.mock(NetworkMessage.class);
    CompletableFuture<Boolean> result = transport.broadcast(msg, true);
    assertNotNull(result);
    assertTrue(result.isDone());
  }

  /**
   * Validates that the transport can be started and stopped multiple times without error,
   * demonstrating idempotent lifecycle management.
   */
  @Test
  public void test_startStopIdempotent() {
    ServerTransport transport = currentTransport.get();
    int port1 = uniquePort();

    transport.start(port1);
    Channel tcpChannel1 = transport.tcpServerChannel();
    Channel udpChannel1 = transport.udpChannel();
    assertTrue(tcpChannel1.isActive());
    assertTrue(udpChannel1.isActive());

    transport.stop();
    assertFalse(tcpChannel1.isActive());
    assertFalse(udpChannel1.isActive());

    int port2 = uniquePort();
    transport.start(port2);
    Channel tcpChannel2 = transport.tcpServerChannel();
    Channel udpChannel2 = transport.udpChannel();
    assertNotEquals(tcpChannel1, tcpChannel2);
    assertNotEquals(udpChannel1, udpChannel2);
    assertTrue(tcpChannel2.isActive());
    assertTrue(udpChannel2.isActive());
  }

  /** Validates that calling start() multiple times without stop() is idempotent. */
  @Test
  public void test_doubleStartIsIdempotent() {
    ServerTransport transport = currentTransport.get();
    int port = uniquePort();

    transport.start(port);
    Channel tcpChannel1 = transport.tcpServerChannel();
    Channel udpChannel1 = transport.udpChannel();

    transport.start(port);
    Channel tcpChannel2 = transport.tcpServerChannel();
    Channel udpChannel2 = transport.udpChannel();

    assertSame(tcpChannel1, tcpChannel2);
    assertSame(udpChannel1, udpChannel2);
  }

  /** Validates that calling stop() before start() does not cause errors. */
  @Test
  public void test_stopWithoutStartIsIdempotent() {
    ServerTransport transport = currentTransport.get();
    transport.stop();
    transport.stop();
  }

  /** Validates that multiple transport instances can run concurrently on different ports. */
  @Test
  public void test_multipleTransportsCanCoexist() {
    ServerTransport transport1 = new ServerTransport();
    ServerTransport transport2 = new ServerTransport();
    transports.add(transport1);
    transports.add(transport2);

    int port1 = uniquePort();
    int port2 = uniquePort();

    transport1.start(port1);
    transport2.start(port2);

    assertTrue(transport1.tcpServerChannel().isActive());
    assertTrue(transport1.udpChannel().isActive());
    assertTrue(transport2.tcpServerChannel().isActive());
    assertTrue(transport2.udpChannel().isActive());

    transport1.stop();
    transport2.stop();

    assertFalse(transport1.tcpServerChannel().isActive());
    assertFalse(transport2.tcpServerChannel().isActive());
  }

  /** Validates that fallback character classes are assigned in round-robin order. */
  @Test
  public void test_fallbackCharacterClassesRotate() {
    ServerTransport transport = currentTransport.get();
    PreRunConfiguration.multiplayerCharacterClasses(
        CharacterClass.THE_LAST_HOUR_ROGUE, CharacterClass.THE_LAST_HOUR_CHAR03);

    assertEquals(
        CharacterClass.THE_LAST_HOUR_ROGUE,
        transport.selectedCharacterClass(new ConnectRequest((short) 1, "player1")));
    assertEquals(
        CharacterClass.THE_LAST_HOUR_CHAR03,
        transport.selectedCharacterClass(new ConnectRequest((short) 1, "player2")));
    assertEquals(
        CharacterClass.THE_LAST_HOUR_ROGUE,
        transport.selectedCharacterClass(new ConnectRequest((short) 1, "player3")));
  }

  /** Validates that explicit character-class requests do not consume the fallback rotation. */
  @Test
  public void test_explicitCharacterClassDoesNotAdvanceFallbackRotation() {
    ServerTransport transport = currentTransport.get();
    PreRunConfiguration.multiplayerCharacterClasses(
        CharacterClass.THE_LAST_HOUR_ROGUE, CharacterClass.THE_LAST_HOUR_CHAR03);

    assertEquals(
        CharacterClass.HUNTER,
        transport.selectedCharacterClass(
            new ConnectRequest(
                (short) 1, "player1", 0, new byte[0], Optional.of(CharacterClass.HUNTER))));
    assertEquals(
        CharacterClass.THE_LAST_HOUR_ROGUE,
        transport.selectedCharacterClass(new ConnectRequest((short) 1, "player2")));
  }

  /**
   * Verifies the player cap rejects fresh identities without blocking retained reconnects.
   *
   * @param enteredName name provided by the restarting client
   */
  @ParameterizedTest
  @ValueSource(strings = {"GoALeitsahne", "GoAleitsahne", "different-player", "invalid_name", ""})
  public void playerCapRejectsFreshIdentityButAllowsRetainedReconnect(String enteredName)
      throws Exception {
    ServerTransport transport = currentTransport.get();
    transport.stop();
    transports.remove(transport);
    transport = new ServerTransport(1);
    transports.add(transport);
    currentTransport.set(transport);
    short retainedClientId = 7;
    byte[] reconnectToken = new byte[] {1, 2, 3};
    Entity retainedPlayer = new Entity();
    ClientState retainedState =
        new ClientState(
            retainedClientId,
            "GoALeitsahne",
            ServerRuntime.SESSION_ID,
            reconnectToken,
            CharacterClass.WIZARD);
    retainedState.playerEntity(retainedPlayer);
    Session retainedSession = testSession(new ArrayList<>());
    Mockito.when(retainedSession.tcpCtx().channel().isActive()).thenReturn(false);
    retainedSession.attachClientState(retainedState);
    sessionsMap(transport).put(retainedSession.tcpCtx().channel().id(), retainedSession);
    clientIdToSessionMap(transport).put(retainedClientId, retainedSession);
    clientIdToNameMap(transport).put(retainedClientId, retainedState.username());

    try {
      Mockito.when(retainedSession.tcpCtx().channel().isActive()).thenReturn(true);
      List<NetworkMessage> freshMessages = new ArrayList<>();
      Session freshSession = testSession(freshMessages);
      invokeConnectRequest(
          transport,
          freshSession,
          new ConnectRequest(NetworkConfig.PROTOCOL_VERSION, "fresh-player"));

      assertTrue(freshSession.clientState().isEmpty());
      assertEquals(1, clientIdToSessionMap(transport).size());
      assertEquals(1, nextClientId(transport).get());
      assertEquals(1, freshMessages.size());
      ConnectReject rejection = (ConnectReject) freshMessages.getFirst();
      assertEquals(
          ConnectReject.Reason.SERVER_FULL, ConnectReject.Reason.fromCode(rejection.reason()));

      Mockito.when(retainedSession.tcpCtx().channel().isActive()).thenReturn(false);
      List<NetworkMessage> reconnectMessages = new ArrayList<>();
      Session reconnectSession = testSession(reconnectMessages);
      invokeConnectRequest(
          transport,
          reconnectSession,
          new ConnectRequest(
              NetworkConfig.PROTOCOL_VERSION,
              enteredName,
              ServerRuntime.SESSION_ID,
              reconnectToken));

      assertSame(retainedState, reconnectSession.clientState().orElseThrow());
      assertEquals("GoALeitsahne", retainedState.username());
      assertEquals("GoALeitsahne", clientIdToNameMap(transport).get(retainedClientId));
      assertEquals(retainedClientId, retainedState.clientId());
      assertSame(retainedPlayer, retainedState.playerEntity().orElseThrow());
      assertEquals(1, clientIdToSessionMap(transport).size());
      assertEquals(1, nextClientId(transport).get());
      assertFalse(retainedState.verifyToken(reconnectToken));
      assertEquals(
          CharacterClass.WIZARD, reconnectSession.clientState().orElseThrow().characterClass());
      assertSame(reconnectSession, clientIdToSessionMap(transport).get(retainedClientId));
      assertTrue(reconnectMessages.stream().anyMatch(ConnectAck.class::isInstance));
      assertTrue(reconnectMessages.stream().noneMatch(ConnectReject.class::isInstance));
      ConnectAck ack =
          reconnectMessages.stream()
              .filter(ConnectAck.class::isInstance)
              .map(ConnectAck.class::cast)
              .findFirst()
              .orElseThrow();
      assertEquals(retainedState.username(), ack.playerName());
      // Invalid names exercise raw protocol requests above, but cannot be entered in the UI.
      if (!enteredName.isBlank() && !enteredName.contains("_")) {
        verifyRestoredLocalPlayer(enteredName, ack, retainedPlayer.id(), retainedState.username());
      }
    } finally {
      Game.remove(retainedPlayer);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "'  PLAYER7  ', false, true, true, false",
    "stranger, false, true, true, false",
    "stranger, false, false, false, false",
    "stranger, true, true, false, false",
    "player7, true, true, true, false",
    "player7, false, true, true, true",
    "stranger, false, false, false, true"
  })
  void fullServerRestoresOnlyAnIdentifiedOrSoleDisconnectedSeat(
      String name, boolean firstActive, boolean secondActive, boolean restored, boolean staleKey)
      throws Exception {
    ServerTransport transport = new ServerTransport(2);
    transports.add(transport);
    Session first = registeredSession(transport, (short) 7);
    Session second = registeredSession(transport, (short) 8);
    Mockito.when(first.tcpCtx().channel().isActive()).thenReturn(firstActive);
    Mockito.when(second.tcpCtx().channel().isActive()).thenReturn(secondActive);
    ClientState retained = first.clientState().orElseThrow();
    Entity player = new Entity();
    retained.playerEntity(player);
    byte[] oldToken = retained.sessionToken().clone();
    List<NetworkMessage> messages = new ArrayList<>();
    Session restarted = testSession(messages);
    try {
      invokeConnectRequest(
          transport,
          restarted,
          new ConnectRequest(
              NetworkConfig.PROTOCOL_VERSION,
              name,
              ServerRuntime.SESSION_ID,
              staleKey ? new byte[] {9, 9, 9} : new byte[0]));
      if (restored) {
        assertSame(retained, restarted.clientState().orElseThrow());
        assertSame(player, retained.playerEntity().orElseThrow());
        assertEquals("player7", retained.username());
        assertEquals(2, transport.clientIdToSessionMap().size());
        assertSame(second, transport.clientIdToSessionMap().get((short) 8));
        ConnectAck ack = (ConnectAck) messages.getFirst();
        assertEquals((short) 7, ack.clientId());
        assertTrue(retained.verifyToken(ack.sessionToken()));
        assertFalse(retained.verifyToken(oldToken));
      } else {
        assertTrue(restarted.clientState().isEmpty());
        assertSame(first, transport.clientIdToSessionMap().get((short) 7));
        assertEquals(
            ConnectReject.Reason.SERVER_FULL,
            ConnectReject.Reason.fromCode(((ConnectReject) messages.getFirst()).reason()));
      }
    } finally {
      Game.remove(player);
      transport.stop();
      transports.remove(transport);
    }
  }

  private void verifyRestoredLocalPlayer(
      String enteredName, ConnectAck serverAck, int entityId, String retainedName)
      throws Exception {
    String previousUsername = PreRunConfiguration.username();
    ClientNetwork client = new ClientNetwork();
    client.initialize("127.0.0.1", TEST_PORT, enteredName, Optional.empty());
    Session clientSession = testSession(new ArrayList<>());
    Field sessionField = ClientNetwork.class.getDeclaredField("session");
    sessionField.setAccessible(true);
    sessionField.set(client, clientSession);
    Game.removeAllEntities();
    PreRunConfiguration.username(enteredName);
    AtomicInteger localFlag = new AtomicInteger();
    HeroBuilder builder = Mockito.mock(HeroBuilder.class, Mockito.RETURNS_SELF);
    Mockito.when(builder.isLocalPlayer(Mockito.anyBoolean()))
        .thenAnswer(
            invocation -> {
              localFlag.set(invocation.getArgument(0) ? 1 : 0);
              return builder;
            });
    Mockito.when(builder.build())
        .thenAnswer(
            ignored -> {
              Entity hero = new Entity(entityId);
              hero.add(new PlayerComponent(localFlag.get() == 1, retainedName));
              return hero;
            });
    // Only bypass credential disk writes and graphics-dependent hero construction.
    try (var diskWrites = Mockito.mockStatic(ClientNetwork.class);
        var heroes = Mockito.mockStatic(HeroBuilder.class)) {
      heroes.when(HeroBuilder::builder).thenReturn(builder);
      var converter = new ConnectAckConverter();
      ConnectAck ack =
          converter.fromProto(
              converter.parser().parseFrom(converter.toProto(serverAck).toByteArray()));
      Method acknowledge =
          ClientNetwork.class.getDeclaredMethod(
              "onConnectAck", short.class, int.class, byte[].class, String.class, String.class);
      acknowledge.setAccessible(true);
      acknowledge.invoke(
          client,
          ack.clientId(),
          ack.sessionId(),
          ack.sessionToken(),
          ack.trackingRoomId(),
          ack.playerName());
      // No lifecycle drain: the authoritative name must already be available for inbound spawns.
      Method spawn = LastHourClient.class.getDeclaredMethod("spawnPlayer", EntitySpawnEvent.class);
      spawn.setAccessible(true);
      spawn.invoke(
          null,
          new EntitySpawnEvent(
              entityId,
              null,
              null,
              new PlayerComponent(false, retainedName),
              (byte) CharacterClass.WIZARD.ordinal()));
      assertEquals(entityId, Game.player().orElseThrow().id());
      assertEquals(retainedName, PreRunConfiguration.username());
      assertEquals(retainedName, clientSession.clientState().orElseThrow().username());
    } finally {
      client.shutdown("test");
      Game.removeAllEntities();
      PreRunConfiguration.username(previousUsername);
    }
  }

  /** Invalid keys cannot restore below capacity; a valid key replaces even a stale active TCP. */
  @Test
  public void restoringRequiresCurrentTokenAndServerSession() throws Exception {
    ServerTransport transport = currentTransport.get();
    byte[] token = {1, 2, 3};
    ClientState retained =
        new ClientState(
            (short) 7, "retained-player", ServerRuntime.SESSION_ID, token, CharacterClass.WIZARD);
    Session oldSession = testSession(new ArrayList<>());
    oldSession.attachClientState(retained);
    Mockito.when(oldSession.tcpCtx().channel().isActive()).thenReturn(false);
    clientIdToSessionMap(transport).put(retained.clientId(), oldSession);
    clientIdToNameMap(transport).put(retained.clientId(), retained.username());

    for (ConnectRequest request :
        List.of(
            new ConnectRequest(
                NetworkConfig.PROTOCOL_VERSION,
                retained.username(),
                ServerRuntime.SESSION_ID,
                new byte[] {4, 5, 6}),
            new ConnectRequest(
                NetworkConfig.PROTOCOL_VERSION,
                retained.username(),
                ServerRuntime.SESSION_ID ^ 1,
                token))) {
      List<NetworkMessage> messages = new ArrayList<>();
      Session candidate = testSession(messages);
      invokeConnectRequest(transport, candidate, request);
      assertTrue(candidate.clientState().isEmpty());
      assertTrue(messages.getFirst() instanceof ConnectReject);
      assertSame(oldSession, clientIdToSessionMap(transport).get(retained.clientId()));
      assertTrue(retained.verifyToken(token));
    }

    Mockito.when(oldSession.tcpCtx().channel().isActive()).thenReturn(true);
    List<NetworkMessage> activeMessages = new ArrayList<>();
    Session activeCandidate = testSession(activeMessages);
    invokeConnectRequest(
        transport,
        activeCandidate,
        new ConnectRequest(
            NetworkConfig.PROTOCOL_VERSION, "different-player", ServerRuntime.SESSION_ID, token));
    assertSame(retained, activeCandidate.clientState().orElseThrow());
    assertTrue(activeMessages.getFirst() instanceof ConnectAck);
    assertSame(activeCandidate, clientIdToSessionMap(transport).get(retained.clientId()));
    Mockito.verify(oldSession.tcpCtx()).close();
  }

  /**
   * Validates that UDP registration marks the session as UDP-ready and stores the sender mapping.
   */
  @Test
  public void test_udpRegisterActivatesSession() throws Exception {
    ServerTransport transport = currentTransport.get();
    AtomicInteger tcpCalls = new AtomicInteger();
    Session session = testSession(tcpCalls);
    byte[] token = new byte[] {1, 2, 3};
    short clientId = 4;
    session.attachClientState(
        new ClientState(
            clientId, "player", ServerRuntime.SESSION_ID, token, CharacterClass.WIZARD));
    clientIdToSessionMap(transport).put(clientId, session);
    InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 25000);

    invokeUdpRegister(
        transport, sender, session, new RegisterUdp(ServerRuntime.SESSION_ID, token, clientId));

    assertTrue(session.udpReady());
    assertEquals(Optional.of(sender), session.udpAddress());
    assertEquals(clientId, udpToClientIdMap(transport).get(sender));
    assertEquals(1, tcpCalls.get());
  }

  /** Validates that stale UDP mappings are removed without closing the TCP session. */
  @Test
  public void test_expireStaleUdpSessionsClearsMapping() throws Exception {
    ServerTransport transport = currentTransport.get();
    Session session = testSession(new AtomicInteger());
    short clientId = 5;
    session.attachClientState(
        new ClientState(
            clientId,
            "player",
            ServerRuntime.SESSION_ID,
            new byte[] {4, 5, 6},
            CharacterClass.WIZARD));
    session.udpAddress(new InetSocketAddress("127.0.0.1", 25001));
    session.markUdpActivity();
    session.udpReady(true);
    clientIdToSessionMap(transport).put(clientId, session);
    InetSocketAddress udpAddress = session.udpAddress().orElseThrow();
    udpToClientIdMap(transport).put(udpAddress, clientId);

    transport.expireStaleUdpSessions(session.udpLastSeenTimeMs() + 4_501L);

    assertFalse(session.udpReady());
    assertFalse(udpToClientIdMap(transport).containsKey(udpAddress));
    assertFalse(session.isClosed());
  }

  /** Validates that the same client can reactivate UDP after the stale mapping was removed. */
  @Test
  public void test_udpCanReregisterAfterStaleExpiry() throws Exception {
    ServerTransport transport = currentTransport.get();
    AtomicInteger tcpCalls = new AtomicInteger();
    Session session = testSession(tcpCalls);
    byte[] token = new byte[] {7, 8, 9};
    short clientId = 6;
    InetSocketAddress originalSender = new InetSocketAddress("127.0.0.1", 25002);
    InetSocketAddress newSender = new InetSocketAddress("127.0.0.1", 25003);
    session.attachClientState(
        new ClientState(
            clientId, "player", ServerRuntime.SESSION_ID, token, CharacterClass.WIZARD));
    clientIdToSessionMap(transport).put(clientId, session);

    invokeUdpRegister(
        transport,
        originalSender,
        session,
        new RegisterUdp(ServerRuntime.SESSION_ID, token, clientId));
    transport.expireStaleUdpSessions(session.udpLastSeenTimeMs() + 4_501L);
    invokeUdpRegister(
        transport, newSender, session, new RegisterUdp(ServerRuntime.SESSION_ID, token, clientId));

    assertTrue(session.udpReady());
    assertEquals(Optional.of(newSender), session.udpAddress());
    assertEquals(clientId, udpToClientIdMap(transport).get(newSender));
    assertEquals(2, tcpCalls.get());
  }

  /** Verifies explicit snapshot acknowledgements update client snapshot state. */
  @Test
  public void snapshotAckUpdatesClientSnapshotSyncState() throws Exception {
    ServerTransport transport = currentTransport.get();
    Session session = registeredSession(transport, (short) 7);

    invokeSnapshotAck(transport, session, new SnapshotAck(20));

    assertEquals(20, session.clientState().orElseThrow().snapshotSync().lastAckedSnapshotTick());
  }

  /** Verifies piggybacked input snapshot acknowledgements update client snapshot state. */
  @Test
  public void inputMessageSnapshotAckUpdatesClientSnapshotSyncState() throws Exception {
    ServerTransport transport = currentTransport.get();
    Session session = registeredSession(transport, (short) 8);

    invokeInputMessage(
        transport,
        session,
        new InputMessage(
            ServerRuntime.SESSION_ID,
            1,
            (short) 1,
            Optional.of(30),
            InputMessage.Action.MOVE,
            new InputMessage.Move(Vector2.of(1, 0))));

    assertEquals(30, session.clientState().orElseThrow().snapshotSync().lastAckedSnapshotTick());
  }

  /** Verifies piggybacked snapshot acknowledgements survive input sequence rejection. */
  @Test
  public void implausibleInputSnapshotAckUpdatesClientSnapshotSyncState() throws Exception {
    ServerTransport transport = currentTransport.get();
    Session session = registeredSession(transport, (short) 8);
    ClientState state = session.clientState().orElseThrow();
    state.advanceProcessedSeq(5);

    invokeInputMessage(
        transport,
        session,
        new InputMessage(
            ServerRuntime.SESSION_ID,
            1,
            (short) 4,
            Optional.of(40),
            InputMessage.Action.MOVE,
            new InputMessage.Move(Vector2.of(1, 0))));

    assertEquals(40, state.snapshotSync().lastAckedSnapshotTick());
  }

  /** Verifies stale snapshot acknowledgements do not move the client backwards. */
  @Test
  public void olderSnapshotAckDoesNotMoveBackwards() throws Exception {
    ServerTransport transport = currentTransport.get();
    Session session = registeredSession(transport, (short) 9);

    invokeSnapshotAck(transport, session, new SnapshotAck(20));
    invokeSnapshotAck(transport, session, new SnapshotAck(19));

    assertEquals(20, session.clientState().orElseThrow().snapshotSync().lastAckedSnapshotTick());
  }

  /** Verifies one-shot debug telemetry requests require the explicit telemetry opt-in. */
  @Test
  public void debugTelemetryOnceSendsSnapshotResponse() throws Exception {
    ServerTransport transport = currentTransport.get();
    transport.start(uniquePort());
    List<NetworkMessage> tcpMessages = new CopyOnWriteArrayList<>();
    Session session = registeredSession(transport, (short) 10, tcpMessages);

    dispatch(session, new DebugTelemetryRequest(55L, DebugTelemetryRequest.Mode.ONCE, 0));

    assertTrue(tcpMessages.isEmpty());

    enableDebugTelemetry();
    dispatch(session, new DebugTelemetryRequest(55L, DebugTelemetryRequest.Mode.ONCE, 0));

    assertEquals(1, tcpMessages.size());
    assertTrue(tcpMessages.getFirst() instanceof DebugTelemetrySnapshot);
    DebugTelemetrySnapshot snapshot = (DebugTelemetrySnapshot) tcpMessages.getFirst();
    assertEquals(55L, snapshot.requestId());
    assertEquals(1, snapshot.clients().size());
  }

  /** Verifies debug pings require the explicit telemetry opt-in. */
  @Test
  public void debugPingSendsPongResponse() throws Exception {
    ServerTransport transport = currentTransport.get();
    transport.start(uniquePort());
    List<NetworkMessage> tcpMessages = new CopyOnWriteArrayList<>();
    Session session = registeredSession(transport, (short) 11, tcpMessages);

    dispatch(session, new DebugPing(56L, 123_456L, 13.5f));

    assertTrue(tcpMessages.isEmpty());

    enableDebugTelemetry();
    dispatch(session, new DebugPing(56L, 123_456L, 13.5f));

    assertEquals(1, tcpMessages.size());
    assertTrue(tcpMessages.getFirst() instanceof DebugPong);
    DebugPong pong = (DebugPong) tcpMessages.getFirst();
    assertEquals(56L, pong.requestId());
    assertEquals(123_456L, pong.clientTimeNanos());
    assertTrue(pong.serverSendTimeMs() >= pong.serverReceiveTimeMs());
    assertEquals(13.5f, session.clientState().orElseThrow().rttEstimateMs(), 0.001f);
  }

  /** Verifies debug telemetry request intervals are clamped to the production policy. */
  @Test
  public void debugTelemetryStreamIntervalIsClamped() {
    ServerTransport transport = currentTransport.get();

    assertEquals(1_000, transport.debugTelemetryIntervalMs(0));
    assertEquals(250, transport.debugTelemetryIntervalMs(1));
    assertEquals(1_500, transport.debugTelemetryIntervalMs(1_500));
  }

  /** Verifies debug telemetry streaming stops on request. */
  @Test
  public void debugTelemetryStreamStopsOnRequest() throws Exception {
    enableDebugTelemetry();
    ServerTransport transport = currentTransport.get();
    transport.start(uniquePort());
    List<NetworkMessage> tcpMessages = new CopyOnWriteArrayList<>();
    Session session = registeredSession(transport, (short) 12, tcpMessages);

    dispatch(session, new DebugTelemetryRequest(57L, DebugTelemetryRequest.Mode.START_STREAM, 1));
    assertEquals(1, tcpMessages.size());
    dispatch(session, new DebugTelemetryRequest(57L, DebugTelemetryRequest.Mode.STOP_STREAM, 1));
    int stoppedCount = tcpMessages.size();
    Thread.sleep(330L);

    assertEquals(stoppedCount, tcpMessages.size());
  }

  /** Verifies debug telemetry streaming stops when a reliable snapshot send fails. */
  @Test
  public void debugTelemetryStreamStopsOnFailedSend() throws Exception {
    enableDebugTelemetry();
    ServerTransport transport = currentTransport.get();
    transport.start(uniquePort());
    List<NetworkMessage> tcpMessages = new CopyOnWriteArrayList<>();
    Session session = registeredSession(transport, (short) 13, tcpMessages, false);

    dispatch(session, new DebugTelemetryRequest(58L, DebugTelemetryRequest.Mode.START_STREAM, 1));
    int failedSendCount = tcpMessages.size();
    Thread.sleep(330L);

    assertEquals(1, failedSendCount);
    assertEquals(failedSendCount, tcpMessages.size());
  }

  private void enableDebugTelemetry() {
    NetworkConfig.DEBUG_TELEMETRY_ENABLED = true;
  }

  private Session testSession(AtomicInteger tcpCalls) {
    ChannelHandlerContext ctx = Mockito.mock(ChannelHandlerContext.class);
    Channel channel = Mockito.mock(Channel.class);
    ChannelId channelId = Mockito.mock(ChannelId.class);
    Mockito.when(ctx.channel()).thenReturn(channel);
    Mockito.when(channel.isActive()).thenReturn(true);
    Mockito.when(channel.isWritable()).thenReturn(true);
    Mockito.when(channel.id()).thenReturn(channelId);
    return new Session(
        ctx,
        (target, msg) -> CompletableFuture.completedFuture(true),
        (channelCtx, msg) -> {
          tcpCalls.incrementAndGet();
          return CompletableFuture.completedFuture(true);
        });
  }

  private Session testSession(List<NetworkMessage> tcpMessages) {
    return testSession(tcpMessages, true);
  }

  private Session testSession(List<NetworkMessage> tcpMessages, boolean tcpSuccess) {
    ChannelHandlerContext ctx = Mockito.mock(ChannelHandlerContext.class);
    Channel channel = Mockito.mock(Channel.class);
    ChannelId channelId = Mockito.mock(ChannelId.class);
    Mockito.when(ctx.channel()).thenReturn(channel);
    Mockito.when(channel.isActive()).thenReturn(true);
    Mockito.when(channel.isWritable()).thenReturn(true);
    Mockito.when(channel.id()).thenReturn(channelId);
    return new Session(
        ctx,
        (target, msg) -> CompletableFuture.completedFuture(true),
        (channelCtx, msg) -> {
          tcpMessages.add(msg);
          return CompletableFuture.completedFuture(tcpSuccess);
        });
  }

  private Session registeredSession(ServerTransport transport, short clientId) throws Exception {
    Session session = testSession(new AtomicInteger());
    session.attachClientState(
        new ClientState(
            clientId,
            "player" + clientId,
            ServerRuntime.SESSION_ID,
            new byte[] {1, 2, 3},
            CharacterClass.WIZARD));
    session.clientState().orElseThrow().initialWorldReady(true);
    sessionsMap(transport).put(session.tcpCtx().channel().id(), session);
    clientIdToSessionMap(transport).put(clientId, session);
    return session;
  }

  private Session registeredSession(
      ServerTransport transport, short clientId, List<NetworkMessage> tcpMessages)
      throws Exception {
    return registeredSession(transport, clientId, tcpMessages, true);
  }

  private Session registeredSession(
      ServerTransport transport,
      short clientId,
      List<NetworkMessage> tcpMessages,
      boolean tcpSuccess)
      throws Exception {
    Session session = testSession(tcpMessages, tcpSuccess);
    session.attachClientState(
        new ClientState(
            clientId,
            "player" + clientId,
            ServerRuntime.SESSION_ID,
            new byte[] {1, 2, 3},
            CharacterClass.WIZARD));
    session.clientState().orElseThrow().initialWorldReady(true);
    sessionsMap(transport).put(session.tcpCtx().channel().id(), session);
    clientIdToSessionMap(transport).put(clientId, session);
    return session;
  }

  @SuppressWarnings("unchecked")
  private Map<ChannelId, Session> sessionsMap(ServerTransport transport) throws Exception {
    Field field = ServerTransport.class.getDeclaredField("sessions");
    field.setAccessible(true);
    return (Map<ChannelId, Session>) field.get(transport);
  }

  @SuppressWarnings("unchecked")
  private Map<Short, Session> clientIdToSessionMap(ServerTransport transport) throws Exception {
    Field field = ServerTransport.class.getDeclaredField("clientIdToSession");
    field.setAccessible(true);
    return (Map<Short, Session>) field.get(transport);
  }

  @SuppressWarnings("unchecked")
  private Map<Short, String> clientIdToNameMap(ServerTransport transport) throws Exception {
    Field field = ServerTransport.class.getDeclaredField("clientIdToName");
    field.setAccessible(true);
    return (Map<Short, String>) field.get(transport);
  }

  private AtomicInteger nextClientId(ServerTransport transport) throws Exception {
    Field field = ServerTransport.class.getDeclaredField("nextClientId");
    field.setAccessible(true);
    return (AtomicInteger) field.get(transport);
  }

  @SuppressWarnings("unchecked")
  private Map<InetSocketAddress, Short> udpToClientIdMap(ServerTransport transport)
      throws Exception {
    Field field = ServerTransport.class.getDeclaredField("udpToClientId");
    field.setAccessible(true);
    return (Map<InetSocketAddress, Short>) field.get(transport);
  }

  private void invokeUdpRegister(
      ServerTransport transport, InetSocketAddress sender, Session session, RegisterUdp registerUdp)
      throws Exception {
    Method method =
        ServerTransport.class.getDeclaredMethod(
            "onUdpRegister", InetSocketAddress.class, Session.class, RegisterUdp.class);
    method.setAccessible(true);
    method.invoke(transport, sender, session, registerUdp);
  }

  private void invokeSnapshotAck(ServerTransport transport, Session session, SnapshotAck ack)
      throws Exception {
    Method method =
        ServerTransport.class.getDeclaredMethod("onSnapshotAck", Session.class, SnapshotAck.class);
    method.setAccessible(true);
    method.invoke(transport, session, ack);
  }

  private void invokeInputMessage(ServerTransport transport, Session session, InputMessage message)
      throws Exception {
    Method method =
        ServerTransport.class.getDeclaredMethod(
            "onInputMessage", Session.class, InputMessage.class);
    method.setAccessible(true);
    method.invoke(transport, session, message);
  }

  private void invokeConnectRequest(
      ServerTransport transport, Session session, ConnectRequest request) throws Exception {
    Method method =
        ServerTransport.class.getDeclaredMethod(
            "onConnectRequest", Session.class, ConnectRequest.class);
    method.setAccessible(true);
    method.invoke(transport, session, request);
  }

  private void dispatch(Session session, NetworkMessage message) {
    Game.network().messageDispatcher().dispatch(session, message);
  }
}
