package engine.systems;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import engine.utils.components.draw.FrameBufferPool;
import engine.utils.components.draw.shader.ShaderList;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DrawSystemFboOwnershipTest {
  @ParameterizedTest
  @CsvSource({
    "scene,true",
    "intermediate,true",
    "entity,true",
    "scene,false",
    "intermediate,false",
    "entity,false"
  })
  void returnsAcquiredFbosWhenAllocationOrProcessingFails(String pass, boolean failAllocation)
      throws Exception {
    FrameBufferPool pool = mock(FrameBufferPool.class);
    FrameBuffer first = mock(FrameBuffer.class);
    FrameBuffer second = mock(FrameBuffer.class);
    IllegalStateException failure = new IllegalStateException("render failed");
    if (failAllocation) {
      when(pool.obtain(anyInt(), anyInt())).thenReturn(first).thenThrow(failure);
    } else {
      when(pool.obtain(anyInt(), anyInt())).thenReturn(first, second);
      when(first.getColorBufferTexture()).thenThrow(failure);
    }

    var instance = DrawSystem.class.getDeclaredField("INSTANCE");
    instance.setAccessible(true);
    Object previous = instance.get(null);
    try (var pools = mockStatic(FrameBufferPool.class)) {
      pools.when(FrameBufferPool::getInstance).thenReturn(pool);
      var constructor = DrawSystem.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      DrawSystem drawSystem = constructor.newInstance();
      var width = DrawSystem.class.getDeclaredField("stableWidth");
      width.setAccessible(true);
      width.setInt(drawSystem, 16);

      assertSame(
          failure, assertThrows(IllegalStateException.class, () -> renderPass(drawSystem, pass)));
      verify(pool).free(first);
      verify(pool, failAllocation ? never() : times(1)).free(second);
      if (pass.equals("scene")) verify(pool).update();
    } finally {
      instance.set(null, previous);
    }
  }

  private void renderPass(DrawSystem drawSystem, String pass) throws Throwable {
    switch (pass) {
      case "scene" -> drawSystem.render(0);
      case "entity" -> drawSystem.processShaders(new TextureRegion(), new ShaderList());
      case "intermediate" -> {
        var method =
            DrawSystem.class.getDeclaredMethod(
                "drawToIntermediateFbo", Runnable.class, ShaderList.class, int.class, int.class);
        method.setAccessible(true);
        try {
          method.invoke(drawSystem, (Runnable) () -> {}, new ShaderList(), 16, 16);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      }
      default -> throw new IllegalArgumentException(pass);
    }
  }
}
