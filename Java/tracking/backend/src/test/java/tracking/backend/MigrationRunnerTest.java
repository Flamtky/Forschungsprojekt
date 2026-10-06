package tracking.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationRunnerTest {
  @Test
  void registersV001BeforeV002AndPackagesBothScripts() throws IOException {
    List<MigrationRunner.Migration> migrations = MigrationRunner.migrations();

    assertEquals(
        List.of("001", "002", "003"),
        migrations.stream().map(MigrationRunner.Migration::version).toList());
    for (MigrationRunner.Migration migration : migrations) {
      assertFalse(MigrationRunner.readMigration(migration.resource()).isBlank());
    }
    assertTrue(
        MigrationRunner.readMigration(migrations.getLast().resource())
            .contains("replay_responses"));
  }
}
