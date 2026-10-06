package engine.utils.components.draw;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import engine.systems.DrawSystem;
import engine.utils.components.draw.shader.ShaderList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextureGeneratorFboTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void returnsShaderFboAfterReadbackEvenWhenReadbackFails(boolean failReadback) {
    FrameBufferPool pool = mock(FrameBufferPool.class);
    DrawSystem drawSystem = mock(DrawSystem.class);
    TextureMap textureMap = mock(TextureMap.class);
    Texture texture = mock(Texture.class);
    FrameBuffer fbo = mock(FrameBuffer.class);
    Pixmap pixmap = mock(Pixmap.class);
    ShaderList shaders = new ShaderList();
    when(texture.getWidth()).thenReturn(16);
    when(texture.getHeight()).thenReturn(16);
    when(textureMap.textureAt(any())).thenReturn(texture);
    when(drawSystem.processShaders(any(), any())).thenReturn(fbo);
    when(fbo.getWidth()).thenReturn(16);
    when(fbo.getHeight()).thenReturn(16);

    try (var pools = mockStatic(FrameBufferPool.class);
        var draws = mockStatic(DrawSystem.class);
        var textures = mockStatic(TextureMap.class);
        var pixmaps = mockStatic(Pixmap.class)) {
      pools.when(FrameBufferPool::getInstance).thenReturn(pool);
      draws.when(DrawSystem::getInstance).thenReturn(drawSystem);
      textures.when(TextureMap::instance).thenReturn(textureMap);
      var readback = pixmaps.when(() -> Pixmap.createFromFrameBuffer(0, 0, 16, 16));
      if (failReadback) {
        readback.thenThrow(new IllegalStateException("readback failed"));
        assertThrows(
            IllegalStateException.class,
            () -> TextureGenerator.staticRenderShaderTexture("base.png", shaders));
      } else {
        readback.thenReturn(pixmap);
        assertSame(pixmap, TextureGenerator.staticRenderShaderTexture("base.png", shaders));
      }
      verify(fbo).end();
      verify(pool).free(fbo);
    }
  }
}
