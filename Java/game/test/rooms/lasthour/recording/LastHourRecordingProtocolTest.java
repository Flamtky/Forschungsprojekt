package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.network.messages.c2s.DialogResponseMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rooms.lasthour.recording.LastHourRecordingProtocol.SyncPoint;

/** Tests the versioned recording sync payload accepted by the server. */
class LastHourRecordingProtocolTest {
  @Test
  void roundTripsAValidSyncPoint() {
    SyncPoint expected = new SyncPoint(UUID.randomUUID(), 164, 8_200, 20, 1280, 720);

    SyncPoint actual = LastHourRecordingProtocol.parse(expected.toPayload()).orElseThrow();

    assertEquals(expected, actual);
  }

  @Test
  void rejectsMalformedWrongTypeAndUnsupportedPayloads() {
    assertTrue(LastHourRecordingProtocol.parse(null).isEmpty());
    assertTrue(LastHourRecordingProtocol.parse(new DialogResponseMessage.LongValue(12L)).isEmpty());
    assertTrue(
        LastHourRecordingProtocol.parse(
                new DialogResponseMessage.StringList(
                    new String[] {
                      "3", UUID.randomUUID().toString(), "0", "0", "20", "1280", "720"
                    }))
            .isEmpty());
    assertTrue(
        LastHourRecordingProtocol.parse(
                new DialogResponseMessage.StringList(
                    new String[] {"2", "not-a-uuid", "0", "0", "20", "1280", "720"}))
            .isEmpty());
    assertTrue(
        LastHourRecordingProtocol.parse(
                new DialogResponseMessage.StringList(
                    new String[] {"2", null, "0", "0", "20", "1280", "720"}))
            .isEmpty());
  }

  @Test
  void rejectsOutOfRangeSyncValuesBeforeSending() {
    UUID recordingId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class, () -> new SyncPoint(recordingId, -1, 0, 5, 1280, 720));
    assertThrows(
        IllegalArgumentException.class, () -> new SyncPoint(recordingId, 0, 0, 0, 1280, 720));
    assertThrows(IllegalArgumentException.class, () -> new SyncPoint(recordingId, 0, 0, 5, 0, 720));
  }
}
