package engine.network.codec.converters.c2s;

import com.google.protobuf.Parser;
import engine.network.codec.MessageConverter;
import engine.network.messages.c2s.RecordingFinalizationResult;
import java.util.UUID;

/** Converter for client recording-finalization results. */
public final class RecordingFinalizationResultConverter
    implements MessageConverter<
        RecordingFinalizationResult, engine.network.proto.c2s.RecordingFinalizationResult> {
  private static final byte WIRE_TYPE_ID = 29;

  @Override
  public engine.network.proto.c2s.RecordingFinalizationResult toProto(
      RecordingFinalizationResult message) {
    return engine.network.proto.c2s.RecordingFinalizationResult.newBuilder()
        .setCompletionId(message.completionId().toString())
        .setStatus(toProtoStatus(message.status()))
        .setStudyId(message.studyId())
        .setRecordingId(message.recordingId())
        .setFramesPerSecond(message.framesPerSecond())
        .setWidth(message.width())
        .setHeight(message.height())
        .setFormat(message.format())
        .setVideoBytes(message.videoBytes())
        .setFramesWritten(message.framesWritten())
        .setFramesDuplicated(message.framesDuplicated())
        .setFramesDropped(message.framesDropped())
        .setMarkersWritten(message.markersWritten())
        .setFfmpegPlatform(message.ffmpegPlatform())
        .setFfmpegSource(message.ffmpegSource())
        .setFailure(message.failure())
        .setRecoveredAfterCrash(message.recoveredAfterCrash())
        .build();
  }

  @Override
  public RecordingFinalizationResult fromProto(
      engine.network.proto.c2s.RecordingFinalizationResult proto) {
    return new RecordingFinalizationResult(
        UUID.fromString(proto.getCompletionId()),
        fromProtoStatus(proto.getStatus()),
        proto.getStudyId(),
        proto.getRecordingId(),
        proto.getFramesPerSecond(),
        proto.getWidth(),
        proto.getHeight(),
        proto.getFormat(),
        proto.getVideoBytes(),
        proto.getFramesWritten(),
        proto.getFramesDuplicated(),
        proto.getFramesDropped(),
        proto.getMarkersWritten(),
        proto.getFfmpegPlatform(),
        proto.getFfmpegSource(),
        proto.getFailure(),
        proto.getRecoveredAfterCrash());
  }

  @Override
  public Class<RecordingFinalizationResult> domainType() {
    return RecordingFinalizationResult.class;
  }

  @Override
  public Class<engine.network.proto.c2s.RecordingFinalizationResult> protoType() {
    return engine.network.proto.c2s.RecordingFinalizationResult.class;
  }

  @Override
  public Parser<engine.network.proto.c2s.RecordingFinalizationResult> parser() {
    return engine.network.proto.c2s.RecordingFinalizationResult.parser();
  }

  @Override
  public byte wireTypeId() {
    return WIRE_TYPE_ID;
  }

  private static engine.network.proto.c2s.RecordingFinalizationResult.Status toProtoStatus(
      RecordingFinalizationResult.Status status) {
    return switch (status) {
      case COMPLETE -> engine.network.proto.c2s.RecordingFinalizationResult.Status.COMPLETE;
      case FAILED -> engine.network.proto.c2s.RecordingFinalizationResult.Status.FAILED;
    };
  }

  private static RecordingFinalizationResult.Status fromProtoStatus(
      engine.network.proto.c2s.RecordingFinalizationResult.Status status) {
    return switch (status) {
      case COMPLETE -> RecordingFinalizationResult.Status.COMPLETE;
      case FAILED -> RecordingFinalizationResult.Status.FAILED;
      case STATUS_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("Recording finalization status is required");
    };
  }
}
