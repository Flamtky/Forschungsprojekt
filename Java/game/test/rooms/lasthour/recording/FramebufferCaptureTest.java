package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FramebufferCaptureTest {
  @Test
  void waitsForStableDimensionsAndSkipsMissingContextOrMinimizedFramebuffer() {
    Graphics graphics = mock(Graphics.class);
    GL20 gl = mock(GL20.class);
    FramebufferCapture capture = new FramebufferCapture();
    when(graphics.getBackBufferWidth()).thenReturn(4);
    when(graphics.getBackBufferHeight()).thenReturn(2);
    assertNull(capture.capture(graphics, gl));
    verifyNoInteractions(gl);
    assertNotNull(capture.capture(graphics, gl));
    clearInvocations(gl);

    when(graphics.getBackBufferWidth()).thenReturn(8);
    assertNull(capture.capture(graphics, gl));
    verifyNoInteractions(gl);
    assertEquals(8 * 2 * 4, capture.capture(graphics, gl).rgba().length);
    clearInvocations(gl);

    when(graphics.isFullscreen()).thenReturn(true);
    assertNull(capture.capture(graphics, gl));
    when(graphics.getBackBufferHeight()).thenReturn(0);
    assertNull(capture.capture(graphics, gl));
    assertNull(capture.capture(graphics, gl));
    assertNull(capture.capture(graphics, null));
    when(graphics.getBackBufferHeight()).thenReturn(2);
    assertNull(capture.capture(graphics, gl));
    verifyNoInteractions(gl);
    assertNotNull(capture.capture(graphics, gl));
  }

  @Test
  void resetsPackOffsetsBeforeReadRestoresStateAndFlipsRows() {
    Graphics graphics = mock(Graphics.class);
    GL20 gl = mock(GL20.class);
    when(graphics.getBackBufferWidth()).thenReturn(3);
    when(graphics.getBackBufferHeight()).thenReturn(2);
    Map<Integer, Integer> original =
        Map.of(
            GL20.GL_PACK_ALIGNMENT,
            8,
            GL30.GL_PACK_ROW_LENGTH,
            17,
            GL30.GL_PACK_SKIP_ROWS,
            2,
            GL30.GL_PACK_SKIP_PIXELS,
            3);
    Map<Integer, Integer> state = new HashMap<>(original);
    doAnswer(
            invocation -> {
              IntBuffer value = invocation.getArgument(1);
              value.put(0, state.get(invocation.<Integer>getArgument(0)));
              return null;
            })
        .when(gl)
        .glGetIntegerv(anyInt(), any(IntBuffer.class));
    doAnswer(
            invocation -> {
              state.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(gl)
        .glPixelStorei(anyInt(), anyInt());
    doAnswer(
            invocation -> {
              assertEquals(
                  Map.of(
                      GL20.GL_PACK_ALIGNMENT,
                      1,
                      GL30.GL_PACK_ROW_LENGTH,
                      0,
                      GL30.GL_PACK_SKIP_ROWS,
                      0,
                      GL30.GL_PACK_SKIP_PIXELS,
                      0),
                  state);
              ByteBuffer pixels = invocation.getArgument(6);
              assertEquals(3 * 2 * 4, pixels.remaining());
              for (int i = 0; i < pixels.capacity(); i++) pixels.put(i, (byte) i);
              return null;
            })
        .when(gl)
        .glReadPixels(
            eq(0),
            eq(0),
            eq(3),
            eq(2),
            eq(GL20.GL_RGBA),
            eq(GL20.GL_UNSIGNED_BYTE),
            any(ByteBuffer.class));

    FramebufferCapture capture = new FramebufferCapture();
    assertNull(capture.capture(graphics, gl));
    var frame = capture.capture(graphics, gl);
    assertEquals(12, frame.rgba()[0]);
    assertEquals(0, frame.rgba()[12]);
    assertEquals(original, state);
  }
}
