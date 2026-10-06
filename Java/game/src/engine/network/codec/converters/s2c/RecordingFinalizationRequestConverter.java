package engine.network.codec.converters.s2c;

import com.google.protobuf.Parser;
import engine.network.codec.MessageConverter;
import engine.network.messages.s2c.RecordingFinalizationRequest;
import java.util.UUID;

/** Converter for server requests to finalize a local gameplay recording. */
public final class RecordingFinalizationRequestConverter
    implements MessageConverter<
        RecordingFinalizationRequest, engine.network.proto.s2c.RecordingFinalizationRequest> {
  private static final byte WIRE_TYPE_ID = 30;

  @Override
  public engine.network.proto.s2c.RecordingFinalizationRequest toProto(
      RecordingFinalizationRequest message) {
    return engine.network.proto.s2c.RecordingFinalizationRequest.newBuilder()
        .setCompletionId(message.completionId().toString())
        .setReason(message.reason())
        .setTimeoutSeconds(message.timeoutSeconds())
        .setGameplayCompleted(message.gameplayCompleted())
        .setRecordingId(message.recordingId())
        .build();
  }

  @Override
  public RecordingFinalizationRequest fromProto(
      engine.network.proto.s2c.RecordingFinalizationRequest proto) {
    return new RecordingFinalizationRequest(
        UUID.fromString(proto.getCompletionId()),
        proto.getReason(),
        proto.getTimeoutSeconds(),
        proto.getGameplayCompleted(),
        proto.getRecordingId());
  }

  @Override
  public Class<RecordingFinalizationRequest> domainType() {
    return RecordingFinalizationRequest.class;
  }

  @Override
  public Class<engine.network.proto.s2c.RecordingFinalizationRequest> protoType() {
    return engine.network.proto.s2c.RecordingFinalizationRequest.class;
  }

  @Override
  public Parser<engine.network.proto.s2c.RecordingFinalizationRequest> parser() {
    return engine.network.proto.s2c.RecordingFinalizationRequest.parser();
  }

  @Override
  public byte wireTypeId() {
    return WIRE_TYPE_ID;
  }
}
