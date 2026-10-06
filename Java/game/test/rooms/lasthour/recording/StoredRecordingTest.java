package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tracking.core.TrackingJson;

class StoredRecordingTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void loadsOldRecordingWithoutReplacingItWithNewEmptyRecording(boolean interrupted)
      throws Exception {
    FfmpegVideoRecorder old = recording();
    Path directory = old.directory();
    Path video = directory.resolve("recording.mkv");
    byte[] original = Files.readAllBytes(video);
    if (interrupted) {
      Files.move(video, directory.resolve("recording.mkv.part"));
      var metadata = TrackingJson.object(Files.readString(directory.resolve("recording.json")));
      metadata.put("status", "RUNNING");
      metadata.put("videoFramesWritten", 0);
      metadata.put("markersWritten", 0);
      metadata.put("framesDropped", 7);
      metadata.put("framesDuplicated", 13);
      LocalReplayFiles.writeJson(directory.resolve("recording.json"), metadata);
    }
    Path empty = Files.createDirectory(root.resolve("new-empty"));
    String emptyMetadata = "{\"recordingId\":\"" + UUID.randomUUID() + "\",\"status\":\"RUNNING\"}";
    Files.writeString(empty.resolve("recording.json"), emptyMetadata);
    Files.writeString(empty.resolve("recording.mkv.part"), "empty-new-recording");
    Path selected = StoredRecording.find(root, old.recordingId().toString());
    assertEquals(directory, selected);
    var recovered = StoredRecording.finalizeRecording(selected);
    assertEquals(old.recordingId(), recovered.recordingId());
    assertEquals("", LastHourRecording.validationFailure(recovered));
    assertEquals(40, recovered.framesWritten());
    assertEquals(interrupted, recovered.recoveredAfterCrash());
    if (interrupted) {
      assertEquals(7, recovered.framesDropped());
      assertEquals(13, recovered.framesDuplicated());
    }
    assertEquals(
        interrupted,
        TrackingJson.object(Files.readString(directory.resolve("recording.json")))
            .path("recoveredAfterCrash")
            .asBoolean());
    assertEquals(emptyMetadata, Files.readString(empty.resolve("recording.json")));
    assertEquals("empty-new-recording", Files.readString(empty.resolve("recording.mkv.part")));
    assertFalse(Files.exists(directory.resolve("recording.mkv.part")));
    if (interrupted)
      assertArrayEquals(
          original, Files.readAllBytes(directory.resolve("recording-interrupted.mkv")));
    else assertArrayEquals(original, Files.readAllBytes(video));
    // A second restart reads the already finalized old recording without changing its identity.
    assertEquals(recovered, StoredRecording.finalizeRecording(selected));
  }

  @Test
  void unreadableOldRecordingFailsInsteadOfSelectingAnotherRecording() throws Exception {
    FfmpegVideoRecorder old = recording();
    Files.writeString(old.directory().resolve("recording.mkv"), "broken video");
    assertThrows(IOException.class, () -> StoredRecording.finalizeRecording(old.directory()));
    assertNoTemporaryFiles(old.directory());
    assertThrows(IOException.class, () -> StoredRecording.find(root, UUID.randomUUID().toString()));
  }

  @Test
  void failedRemuxRemovesTemporaryVideoAndKeepsCrashOriginal() throws Exception {
    FfmpegVideoRecorder old = recording();
    Path directory = old.directory();
    Files.move(directory.resolve("recording.mkv"), directory.resolve("recording.mkv.part"));
    Files.writeString(directory.resolve("recording.mkv.part"), "broken video");
    var metadata = TrackingJson.object(Files.readString(directory.resolve("recording.json")));
    metadata.put("status", "RUNNING");
    LocalReplayFiles.writeJson(directory.resolve("recording.json"), metadata);
    assertThrows(IOException.class, () -> StoredRecording.finalizeRecording(directory));
    assertEquals("broken video", Files.readString(directory.resolve("recording.mkv.part")));
    assertNoTemporaryFiles(directory);
  }

  private static void assertNoTemporaryFiles(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      assertTrue(
          files.noneMatch(
              path ->
                  path.getFileName().toString().startsWith("recovered-")
                      || path.getFileName().toString().startsWith("decode-")));
    }
  }

  @Test
  void overlappingRequestAndReplayShareTheOldRecordingFinalization() throws Exception {
    FfmpegVideoRecorder old = recording();
    var metadata = TrackingJson.object(Files.readString(old.directory().resolve("recording.json")));
    metadata.put("status", "RUNNING");
    metadata.put("framesDropped", 7);
    metadata.put("framesDuplicated", 13);
    LocalReplayFiles.writeJson(old.directory().resolve("recording.json"), metadata);
    String previousRoot = System.getProperty(FfmpegVideoRecorder.DIRECTORY_PROPERTY);
    var saved = new java.util.HashMap<java.lang.reflect.Field, Object>();
    for (String name :
        new String[] {"recorder", "finishFuture", "retainedRecordingId", "retainedFinishFuture"}) {
      var field = LastHourRecording.class.getDeclaredField(name);
      field.setAccessible(true);
      saved.put(field, field.get(null));
      field.set(null, null);
    }
    System.setProperty(FfmpegVideoRecorder.DIRECTORY_PROPERTY, root.toString());
    try {
      var request = LastHourRecording.finishAsync(old.recordingId().toString());
      var replay = LastHourRecording.finishAsync(old.recordingId().toString());
      assertSame(request, replay);
      var result = replay.get(20, TimeUnit.SECONDS);
      assertEquals(LastHourRecording.Finalization.Status.COMPLETE, result.status());
      assertEquals(old.recordingId().toString(), result.recordingId());
      assertEquals("P001", result.studyId());
      assertTrue(result.recoveredAfterCrash());
      verifyRecoveryFlagInNetworkResultAndSummary(result);
    } finally {
      for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
      if (previousRoot == null) System.clearProperty(FfmpegVideoRecorder.DIRECTORY_PROPERTY);
      else System.setProperty(FfmpegVideoRecorder.DIRECTORY_PROPERTY, previousRoot);
    }
  }

  private void verifyRecoveryFlagInNetworkResultAndSummary(LastHourRecording.Finalization recording)
      throws Exception {
    UUID completion = UUID.randomUUID();
    var convert =
        LastHourRunFinalizer.class.getDeclaredMethod(
            "toNetworkResult", UUID.class, LastHourRecording.Finalization.class);
    convert.setAccessible(true);
    var result =
        (engine.network.messages.c2s.RecordingFinalizationResult)
            convert.invoke(null, completion, recording);
    byte[] wire = engine.network.codec.NetworkCodec.serialize(result);
    result =
        (engine.network.messages.c2s.RecordingFinalizationResult)
            engine.network.codec.NetworkCodec.deserialize(
                io.netty.buffer.Unpooled.wrappedBuffer(wire));
    assertTrue(result.recoveredAfterCrash());
    assertTrue(result.usableForReplay(), "Unreliable counters must not block a recovered replay");
    var state =
        new LastHourFinalizationState(
            completion,
            true,
            "done",
            1,
            90,
            Instant.now(),
            0,
            java.util.List.of(
                new LastHourFinalizationState.ExpectedClient(
                    (short) 1, UUID.randomUUID().toString(), recording.recordingId())));
    state.accept((short) 1, result);
    state.markFinalizationEventPersisted((short) 1);
    state.finish(false, Instant.now());
    var path =
        LastHourRunFinalizer.writeSummary(state, state.runStatus(), root.resolve("session.jsonl"));
    var entry = TrackingJson.object(Files.readString(path)).path("clients").get(0);
    assertTrue(entry.path("recoveredAfterCrash").asBoolean());
    assertTrue(entry.path("usableForReplay").asBoolean());
    assertEquals(7, entry.path("framesDropped").asLong());
    assertEquals(13, entry.path("framesDuplicated").asLong());
  }

  @Test
  void interruptedRecoveryStopsEncoderBeforeRemovingItsTemporaryVideo() throws Exception {
    FfmpegVideoRecorder old = recording();
    Path directory = old.directory();
    Files.move(directory.resolve("recording.mkv"), directory.resolve("recording.mkv.part"));
    var metadata = TrackingJson.object(Files.readString(directory.resolve("recording.json")));
    metadata.put("status", "RUNNING");
    LocalReplayFiles.writeJson(directory.resolve("recording.json"), metadata);
    var executable = BundledFfmpeg.resolve();
    try (var ffmpeg = org.mockito.Mockito.mockStatic(BundledFfmpeg.class)) {
      ffmpeg.when(BundledFfmpeg::resolve).thenReturn(executable);
      Thread.currentThread().interrupt();
      assertThrows(IOException.class, () -> StoredRecording.finalizeRecording(directory));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertNoTemporaryFiles(directory);
    assertTrue(Files.isRegularFile(directory.resolve("recording.mkv.part")));
  }

  private FfmpegVideoRecorder recording() throws Exception {
    var ffmpeg = BundledFfmpeg.resolve();
    assertEquals("bundled", ffmpeg.source());
    var config =
        new FfmpegVideoRecorder.Config(root, "P001", 20, 1280, 720, 23, "veryfast", 2, 64, ffmpeg);
    AtomicLong nanos = new AtomicLong();
    var recorder =
        FfmpegVideoRecorder.start(
            config,
            nanos::get,
            () -> Instant.EPOCH,
            new FfmpegVideoRecorder.NearestNeighborBgrScaler(),
            FfmpegVideoRecorder.FfmpegVideoSink::open,
            ignored -> {});
    recorder.markNextFrame(LastHourRecordingProtocol.INTRO_MARKER, "intro");
    for (int i = 0; i < 40; i++) {
      assertTrue(recorder.captureIfDue(() -> new FfmpegVideoRecorder.RawFrame(2, 2, new byte[16])));
      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
    }
    recorder.close();
    assertEquals(FfmpegVideoRecorder.Status.COMPLETE, recorder.status());
    return recorder;
  }
}
