package engine.network.messages.s2c;

import engine.network.messages.NetworkMessage;

/**
 * Server→client: acknowledgement of connection with assigned clientId.
 *
 * @param clientId the assigned client ID
 * @param sessionId the assigned session ID
 * @param sessionToken the assigned session token
 * @param trackingRoomId stable room ID for client-local history, or blank when tracking is disabled
 * @param playerName authoritative name used to identify the local player in subsequent spawns
 */
public record ConnectAck(
    short clientId, int sessionId, byte[] sessionToken, String trackingRoomId, String playerName)
    implements NetworkMessage {}
