package tracking.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tracking.core.ReplayProtocol;

/**
 * Real PostgreSQL integration, enabled only for an explicitly supplied disposable test database.
 */
class ReplayDatabaseTest {
  @Test
  void twoClientsPersistOnceWithRuntimePrivilegesAndRejectTampering() throws Exception {
    String url = System.getenv("REPLAY_TEST_DATABASE_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank());
    String key = "disposable-replay-test-api-key-with-32-characters";
    var config =
        new BackendConfig(
            "127.0.0.1",
            0,
            url,
            "postgres",
            "replay-test-only",
            Optional.empty(),
            Optional.of(key),
            65536,
            500);
    Database database = new Database(config);
    try (var connection = database.connect();
        var statement = connection.createStatement()) {
      statement.execute(
          "DO $$ BEGIN CREATE ROLE replay_test_runtime LOGIN PASSWORD 'replay-test-only'; "
              + "EXCEPTION WHEN duplicate_object THEN NULL; END $$");
    }
    MigrationRunner.migrate(database, Optional.of("replay_test_runtime"));
    MigrationRunner.migrate(database, Optional.of("replay_test_runtime"));
    var runtimeConfig =
        new BackendConfig(
            "127.0.0.1",
            0,
            url,
            "replay_test_runtime",
            "replay-test-only",
            Optional.empty(),
            Optional.of(key),
            65536,
            500);
    var runtime = new Database(runtimeConfig);
    try (var server = new TrackingHttpServer(runtimeConfig, new TrackingRepository(runtime));
        HttpClient client = HttpClient.newHttpClient()) {
      server.start();
      URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/replay/responses");
      UUID session = UUID.randomUUID();
      for (String study : List.of("TEST-A", "TEST-B")) {
        var plan =
            new ReplayProtocol.Plan(
                1,
                session.toString(),
                UUID.randomUUID().toString(),
                study,
                UUID.randomUUID().toString(),
                100,
                200,
                List.of(
                    new ReplayProtocol.Clip("S1", 3, "E1", "MEDIUM", "NONE", 1000, 0, 2000, 2000)));
        var answers =
            new ReplayProtocol.Responses(
                List.of(
                    new ReplayProtocol.Answer(
                        "S1", new ReplayProtocol.Baseline(1, 2, 3, 4, "HINT"), null)),
                "test",
                "test");
        var ticket = ReplayProtocol.sign(plan, key);
        var submission = new ReplayProtocol.Submission(ticket, answers);
        var first = post(client, endpoint, ReplayProtocol.JSON.writeValueAsString(submission));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(
            first.body(),
            post(client, endpoint, ReplayProtocol.JSON.writeValueAsString(submission)).body());
        var different =
            new ReplayProtocol.Submission(
                ticket, new ReplayProtocol.Responses(answers.answers(), "changed", "test"));
        assertEquals(
            409,
            post(client, endpoint, ReplayProtocol.JSON.writeValueAsString(different)).statusCode());
        var tampered =
            new ReplayProtocol.Submission(
                new ReplayProtocol.Ticket(ticket.planJson(), "bad"), answers);
        assertEquals(
            400,
            post(client, endpoint, ReplayProtocol.JSON.writeValueAsString(tampered)).statusCode());
      }
      assertEquals(400, post(client, endpoint, "null").statusCode());
      assertEquals(400, post(client, endpoint, "{\"video\":\"no video uploads\"}").statusCode());
      assertEquals(413, post(client, endpoint, "x".repeat(65537)).statusCode());
      try (var connection = runtime.connect();
          var statement =
              connection.prepareStatement(
                  "SELECT count(*), min(frustration_mean), count(DISTINCT participant_id) FROM replay_ratings WHERE session_id = ?")) {
        statement.setObject(1, session);
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(2, rows.getInt(1));
          assertEquals(2.0, rows.getDouble(2));
          assertEquals(2, rows.getInt(3));
        }
      }
    }
  }

  private static HttpResponse<String> post(HttpClient client, URI uri, String body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
