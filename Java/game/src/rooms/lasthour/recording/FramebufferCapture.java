package rooms.lasthour.recording;

import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import rooms.lasthour.recording.FfmpegVideoRecorder.RawFrame;

/** Render-thread-only desktop framebuffer readback, including resize and pixel-pack handling. */
final class FramebufferCapture {
  private static final int[] PACK_SETTINGS = {
    GL20.GL_PACK_ALIGNMENT,
    GL30.GL_PACK_ROW_LENGTH,
    GL30.GL_PACK_SKIP_ROWS,
    GL30.GL_PACK_SKIP_PIXELS
  };
  private final IntBuffer integer = BufferUtils.newIntBuffer(1);
  private ByteBuffer buffer;
  private int previousWidth;
  private int previousHeight;
  private boolean previousFullscreen;

  /**
   * Reads a stable desktop framebuffer on the render thread.
   *
   * @param graphics current graphics instance, or null while unavailable
   * @param gl current OpenGL interface, or null while unavailable
   * @return the captured frame, or null while unavailable or changing dimensions
   */
  RawFrame capture(Graphics graphics, GL20 gl) {
    if (graphics == null || gl == null) {
      previousWidth = previousHeight = 0;
      return null;
    }
    int width = graphics.getBackBufferWidth();
    int height = graphics.getBackBufferHeight();
    boolean fullscreen = graphics.isFullscreen();
    boolean changed =
        width != previousWidth || height != previousHeight || fullscreen != previousFullscreen;
    previousWidth = width;
    previousHeight = height;
    previousFullscreen = fullscreen;
    long bytes = (long) width * height * 4;
    if (changed || width <= 0 || height <= 0 || bytes > Integer.MAX_VALUE) {
      return null;
    }
    if (buffer == null || buffer.capacity() != (int) bytes) {
      buffer = BufferUtils.newByteBuffer((int) bytes);
    }
    buffer.clear();
    int[] original = new int[PACK_SETTINGS.length];
    for (int i = 0; i < PACK_SETTINGS.length; i++) {
      integer.clear();
      gl.glGetIntegerv(PACK_SETTINGS[i], integer);
      original[i] = integer.get(0);
      // Desktop OpenGL packing offsets can otherwise write beyond the allocated RGBA buffer.
      gl.glPixelStorei(PACK_SETTINGS[i], i == 0 ? 1 : 0);
    }
    try {
      gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, buffer);
    } finally {
      for (int i = 0; i < PACK_SETTINGS.length; i++) {
        gl.glPixelStorei(PACK_SETTINGS[i], original[i]);
      }
    }
    byte[] pixels = new byte[(int) bytes];
    int rowBytes = width * 4;
    for (int row = 0; row < height; row++) {
      buffer.position((height - row - 1) * rowBytes);
      buffer.get(pixels, row * rowBytes, rowBytes);
    }
    return new RawFrame(width, height, pixels);
  }
}
