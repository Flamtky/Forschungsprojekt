package engine.utils.components.draw;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameBufferPoolTest {
  private final List<FrameBuffer> created = new ArrayList<>();
  private final FrameBufferPool pool =
      new FrameBufferPool(
          (width, height) -> {
            FrameBuffer fbo = newFbo(width, height);
            created.add(fbo);
            return fbo;
          });

  @AfterEach
  void cleanup() {
    pool.dispose();
    created.forEach(fbo -> verify(fbo).dispose());
  }

  @Test
  void resizingBeyondHardLimitReclaimsAvailableFbos() {
    assertDoesNotThrow(
        () -> {
          for (int width = 1; width <= 150; width++) {
            FrameBuffer first = pool.obtain(width, 100);
            FrameBuffer second = pool.obtain(width, 100);
            pool.free(first);
            pool.free(second);
          }
        });
    created.subList(0, 200).forEach(fbo -> verify(fbo).dispose());
    created.subList(200, 300).forEach(fbo -> verify(fbo, never()).dispose());
  }

  @Test
  void reusesExactSizeAtLimitThenEvictsLeastRecentlyUsedAvailableFbo() {
    for (int width = 1; width <= 100; width++) {
      FrameBuffer fbo = pool.obtain(width, 100);
      if (width != 1) pool.free(fbo);
    }

    FrameBuffer reused = pool.obtain(2, 100);
    assertSame(created.get(1), reused);
    created.forEach(fbo -> verify(fbo, never()).dispose());
    pool.free(reused);

    pool.obtain(101, 100);
    verify(created.get(2)).dispose();
    verify(created.getFirst(), never()).dispose();
    verify(reused, never()).dispose();
    for (int index = 3; index < created.size(); index++) {
      verify(created.get(index), never()).dispose();
    }
  }

  @Test
  void throwsOnlyWhileEveryFboIsInUse() {
    for (int width = 1; width <= 100; width++) {
      pool.obtain(width, 100);
    }
    assertThrows(IllegalStateException.class, () -> pool.obtain(101, 100));
    created.forEach(fbo -> verify(fbo, never()).dispose());

    pool.free(created.get(50));
    assertDoesNotThrow(() -> pool.obtain(101, 100));
    verify(created.get(50)).dispose();
  }

  private static FrameBuffer newFbo(int width, int height) {
    FrameBuffer fbo = mock(FrameBuffer.class);
    when(fbo.getWidth()).thenReturn(width);
    when(fbo.getHeight()).thenReturn(height);
    return fbo;
  }
}
