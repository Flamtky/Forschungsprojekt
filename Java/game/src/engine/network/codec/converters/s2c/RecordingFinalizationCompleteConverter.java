package engine.network.codec.converters.s2c;

import com.google.protobuf.Parser;
import engine.network.codec.MessageConverter;
import engine.network.messages.s2c.RecordingFinalizationComplete;
import java.util.UUID;

/** Converter for the coordinated recording-finalization outcome. */
public final class RecordingFinalizationCompleteConverter
    implements MessageConverter<
        RecordingFinalizationComplete, engine.network.proto.s2c.RecordingFinalizationComplete> {
  private static final byte WIRE_TYPE_ID = 31;

  @Override
  public engine.network.proto.s2c.RecordingFinalizationComplete toProto(
      RecordingFinalizationComplete message) {
    return engine.network.proto.s2c.RecordingFinalizationComplete.newBuilder()
        .setCompletionId(message.completionId().toString())
        .setRunStatus(toProtoStatus(message.runStatus()))
        .setMessage(message.message())
        .setReplayLaunchJson(message.replayLaunchJson())
        .build();
  }

  @Override
  public RecordingFinalizationComplete fromProto(
      engine.network.proto.s2c.RecordingFinalizationComplete proto) {
    return new RecordingFinalizationComplete(
        UUID.fromString(proto.getCompletionId()),
        fromProtoStatus(proto.getRunStatus()),
        proto.getMessage(),
        proto.getReplayLaunchJson());
  }

  @Override
  public Class<RecordingFinalizationComplete> domainType() {
    return RecordingFinalizationComplete.class;
  }

  @Override
  public Class<engine.network.proto.s2c.RecordingFinalizationComplete> protoType() {
    return engine.network.proto.s2c.RecordingFinalizationComplete.class;
  }

  @Override
  public Parser<engine.network.proto.s2c.RecordingFinalizationComplete> parser() {
    return engine.network.proto.s2c.RecordingFinalizationComplete.parser();
  }

  @Override
  public byte wireTypeId() {
    return WIRE_TYPE_ID;
  }

  private static engine.network.proto.s2c.RecordingFinalizationComplete.RunStatus toProtoStatus(
      RecordingFinalizationComplete.RunStatus status) {
    return switch (status) {
      case COMPLETE -> engine.network.proto.s2c.RecordingFinalizationComplete.RunStatus.COMPLETE;
      case INVALID -> engine.network.proto.s2c.RecordingFinalizationComplete.RunStatus.INVALID;
      case ABORTED -> engine.network.proto.s2c.RecordingFinalizationComplete.RunStatus.ABORTED;
    };
  }

  private static RecordingFinalizationComplete.RunStatus fromProtoStatus(
      engine.network.proto.s2c.RecordingFinalizationComplete.RunStatus status) {
    return switch (status) {
      case COMPLETE -> RecordingFinalizationComplete.RunStatus.COMPLETE;
      case INVALID -> RecordingFinalizationComplete.RunStatus.INVALID;
      case ABORTED -> RecordingFinalizationComplete.RunStatus.ABORTED;
      case RUN_STATUS_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Run finalization status is required");
    };
  }
}
