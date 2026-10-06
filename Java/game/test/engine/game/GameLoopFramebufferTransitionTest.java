package engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GameLoopFramebufferTransitionTest {
  @Test
  void suppressesReadbackCallbackUntilDisplayTransitionSettles() throws Exception {
    Constructor<GameLoop> constructor = GameLoop.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    GameLoop loop = constructor.newInstance();
    Field transitionFrames = GameLoop.class.getDeclaredField("displayModeTransitionFrames");
    transitionFrames.setAccessible(true);
    var previous = PreRunConfiguration.userOnFrameRendered();
    AtomicInteger captures = new AtomicInteger();
    try {
      PreRunConfiguration.userOnFrameRendered(captures::incrementAndGet);
      for (int remaining = 8; remaining > 0; remaining--) {
        transitionFrames.setInt(loop, remaining);
        loop.notifyFrameRendered();
      }
      assertEquals(0, captures.get());
      transitionFrames.setInt(loop, 0);
      loop.notifyFrameRendered();
      assertEquals(1, captures.get());
    } finally {
      PreRunConfiguration.userOnFrameRendered(previous);
    }
  }
}
