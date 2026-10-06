package engine.network.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.network.codec.converters.c2s.RecordingFinalizationResultConverter;
import engine.network.codec.converters.s2c.RecordingFinalizationCompleteConverter;
import engine.network.messages.NetworkMessage;
import engine.network.messages.c2s.RecordingFinalizationResult;
import engine.network.messages.s2c.RecordingFinalizationComplete;
import engine.network.messages.s2c.RecordingFinalizationRequest;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests the protocol used to coordinate recorder shutdown. */
class RecordingFinalizationConverterTest {
  private static final UUID COMPLETION_ID = UUID.fromString("18eb685f-511f-4a1e-aab7-441dfe4146ef");

  @Test
  void allMessagesRoundTripThroughTheNetworkCodec() throws Exception {
    List<NetworkMessage> messages =
        List.of(
            new RecordingFinalizationRequest(
                COMPLETION_ID, "finished", 90, true, "5ac3a489-0595-4568-84ef-f1139ba8d82c"),
            usableResult(),
            new RecordingFinalizationComplete(
                COMPLETION_ID,
                RecordingFinalizationComplete.RunStatus.COMPLETE,
                "saved",
                "{\"ticket\":\"own-recording\"}"));

    for (NetworkMessage message : messages) {
      byte[] encoded = NetworkCodec.serialize(message);
      assertEquals(message, NetworkCodec.deserialize(Unpooled.wrappedBuffer(encoded)));
    }
  }

  @Test
  void replayUsabilityRequiresAllPilotRecordingEvidence() {
    assertTrue(usableResult().usableForReplay());
    assertTrue(withProfile(30, 1280, 720, "matroska-h264-v1", "bundled").usableForReplay());
    assertFalse(withDroppedFrames(200L, 3L, false).usableForReplay());
    assertFalse(withMarkerCount(0).usableForReplay());
    assertFalse(withStudyId("").usableForReplay());
    assertFalse(withProfile(1, 1280, 720, "matroska-h264-v1", "bundled").usableForReplay());
    assertFalse(withProfile(20, 640, 480, "matroska-h264-v1", "bundled").usableForReplay());
    assertFalse(withProfile(20, 1280, 720, "mp4-h264", "bundled").usableForReplay());
    assertFalse(withProfile(20, 1280, 720, "matroska-h264-v1", "PATH").usableForReplay());
  }

  @Test
  void convertersRejectMissingStatusValues() {
    var resultConverter = new RecordingFinalizationResultConverter();
    var completeConverter = new RecordingFinalizationCompleteConverter();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            resultConverter.fromProto(
                engine.network.proto.c2s.RecordingFinalizationResult.newBuilder()
                    .setCompletionId(COMPLETION_ID.toString())
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            completeConverter.fromProto(
                engine.network.proto.s2c.RecordingFinalizationComplete.newBuilder()
                    .setCompletionId(COMPLETION_ID.toString())
                    .build()));
  }

  private static RecordingFinalizationResult usableResult() {
    return result("P001", 0L, 1);
  }

  @Test
  void replayUsabilityAllowsDropsBelowOnePercentAndExemptsRecoveredRecordings() {
    assertTrue(withDroppedFrames(46_675L, 58L, false).usableForReplay());
    assertTrue(withDroppedFrames(100L, 1L, false).usableForReplay());
    assertFalse(withDroppedFrames(99L, 1L, false).usableForReplay());
    assertFalse(withDroppedFrames(98L, 1L, false).usableForReplay());
    assertTrue(withDroppedFrames(99L, 1L, true).usableForReplay());
    assertTrue(withDroppedFrames(98L, 1L, true).usableForReplay());
  }

  private static RecordingFinalizationResult withDroppedFrames(
      long framesWritten, long framesDropped, boolean recoveredAfterCrash) {
    return result(
        "P001",
        framesDropped,
        1,
        20,
        1280,
        720,
        "matroska-h264-v1",
        "bundled",
        framesWritten,
        recoveredAfterCrash);
  }

  private static RecordingFinalizationResult withMarkerCount(int markersWritten) {
    return result("P001", 0L, markersWritten);
  }

  private static RecordingFinalizationResult withStudyId(String studyId) {
    return result(studyId, 0L, 1);
  }

  private static RecordingFinalizationResult result(
      String studyId, long framesDropped, int markersWritten) {
    return result(
        studyId, framesDropped, markersWritten, 20, 1280, 720, "matroska-h264-v1", "bundled");
  }

  private static RecordingFinalizationResult withProfile(
      int framesPerSecond, int width, int height, String format, String ffmpegSource) {
    return result("P001", 0L, 1, framesPerSecond, width, height, format, ffmpegSource);
  }

  private static RecordingFinalizationResult result(
      String studyId,
      long framesDropped,
      int markersWritten,
      int framesPerSecond,
      int width,
      int height,
      String format,
      String ffmpegSource) {
    return result(
        studyId,
        framesDropped,
        markersWritten,
        framesPerSecond,
        width,
        height,
        format,
        ffmpegSource,
        200L,
        false);
  }

  private static RecordingFinalizationResult result(
      String studyId,
      long framesDropped,
      int markersWritten,
      int framesPerSecond,
      int width,
      int height,
      String format,
      String ffmpegSource,
      long framesWritten,
      boolean recoveredAfterCrash) {
    return new RecordingFinalizationResult(
        COMPLETION_ID,
        RecordingFinalizationResult.Status.COMPLETE,
        studyId,
        "5ac3a489-0595-4568-84ef-f1139ba8d82c",
        framesPerSecond,
        width,
        height,
        format,
        10_000L,
        framesWritten,
        2L,
        framesDropped,
        markersWritten,
        "windows-x86_64",
        ffmpegSource,
        "",
        recoveredAfterCrash);
  }
}
