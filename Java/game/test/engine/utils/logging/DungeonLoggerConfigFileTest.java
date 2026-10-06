package engine.utils.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DungeonLoggerConfigFileTest {
  @TempDir Path directory;

  @Test
  void explicitFileWritesInfoWithoutChangingWarningConsole() throws Exception {
    Logger root = Logger.getLogger("");
    Handler[] handlers = root.getHandlers();
    Level level = root.getLevel();
    PrintStream stderr = System.err;
    Map<Field, Object> previous = new LinkedHashMap<>();
    for (String name :
        new String[] {"initialized", "rootLogger", "consoleHandler", "fileHandler"}) {
      Field field = DungeonLoggerConfig.class.getDeclaredField(name);
      field.setAccessible(true);
      previous.put(field, field.get(null));
      if (name.equals("initialized")) field.set(null, false);
    }
    ByteArrayOutputStream console = new ByteArrayOutputStream();
    Path file = directory.resolve("100%literal").resolve("client.log");
    try (PrintStream output = new PrintStream(console, true, StandardCharsets.UTF_8)) {
      System.setErr(output);
      DungeonLoggerConfig.builder()
          .consoleLevel(Level.WARNING)
          .fileLevel(Level.INFO)
          .logFile(file)
          .build();
      Logger logger = Logger.getLogger("client-log-test");
      logger.info("client-info");
      logger.warning("client-warning");
      logger.fine("client-debug");
      DungeonLoggerConfig.shutdown();
      String content = Files.readString(file);
      assertTrue(content.contains("client-info"));
      assertTrue(content.contains("client-warning"));
      assertFalse(content.contains("client-debug"));
      assertFalse(console.toString(StandardCharsets.UTF_8).contains("client-info"));
      assertTrue(console.toString(StandardCharsets.UTF_8).contains("client-warning"));
    } finally {
      System.setErr(stderr);
      for (Handler handler : root.getHandlers()) {
        root.removeHandler(handler);
        handler.close();
      }
      for (Handler handler : handlers) root.addHandler(handler);
      root.setLevel(level);
      for (var entry : previous.entrySet()) entry.getKey().set(null, entry.getValue());
    }
  }
}
