package rooms.lasthour.adaptation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.FrustrationRisk;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Input;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Judgment;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Observation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tracking.core.TrackingJson;

/**
 * Asks TypeSafe's System One model Jev the two semantic questions of the adaptation policy: is the
 * team stuck (Noul), and how high is each person's frustration risk (Score). Durations, repetition,
 * and pace are computed in code and sent as labels; Jev only judges what the inputs mean.
 *
 * <p>The server reads the API key from {@code TYPESAFE_API_KEY}. Clients never call Jev.
 */
final class LastHourJev {
  private static final URI ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private static final String STUCK = "team_stuck";

  /**
   * Parsed answers plus what was exchanged, for the tracking log.
   *
   * @param judgment typed answers for the policy
   * @param model versioned model that answered
   * @param latencyMs request round trip
   * @param players question label per participant, e.g. {@code player_1}
   * @param state state sent to Jev
   * @param answers raw answers returned by Jev
   */
  record Result(
      Judgment judgment,
      String model,
      long latencyMs,
      Map<String, UUID> players,
      ObjectNode state,
      JsonNode answers) {}

  private final String apiKey;
  private final String model;

  LastHourJev(String apiKey, String model) {
    this.apiKey = apiKey;
    this.model = model;
  }

  /**
   * Uses {@code TYPESAFE_API_KEY} and the pinned model, overridable for later model versions.
   *
   * @return client configured from the server environment
   */
  static LastHourJev fromEnvironment() {
    return new LastHourJev(
        System.getenv("TYPESAFE_API_KEY"),
        System.getProperty("lasthour.adaptation.jevModel", "jev-1.13.0"));
  }

  boolean configured() {
    return apiKey != null && !apiKey.isBlank();
  }

  /**
   * Sends one request for an observation without blocking the game loop.
   *
   * @param observation observation to judge
   * @return typed answers; fails on missing key, transport errors, non-200 responses, or answers
   *     that do not match the questions
   */
  CompletableFuture<Result> judge(Observation observation) {
    if (!configured()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("TYPESAFE_API_KEY is not set"));
    }
    Map<String, UUID> players = labels(observation);
    ObjectNode state = state(observation, players);
    ObjectNode body = TrackingJson.object();
    body.put("model", model);
    body.set("state", state);
    body.set("questions", questions(players));
    HttpRequest request =
        HttpRequest.newBuilder(ENDPOINT)
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    long started = System.nanoTime();
    return CLIENT
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                String text = response.body();
                throw new IllegalStateException(
                    "Jev returned HTTP "
                        + response.statusCode()
                        + ": "
                        + text.substring(0, Math.min(300, text.length())));
              }
              ObjectNode answer = TrackingJson.object(response.body());
              return new Result(
                  parse(answer.path("answers"), players),
                  answer.path("model").asString(),
                  (System.nanoTime() - started) / 1_000_000L,
                  players,
                  state,
                  answer.path("answers"));
            });
  }

  /**
   * Assigns {@code player_1}, {@code player_2}, ... in registration order.
   *
   * @param observation observation whose participants are labelled
   * @return participant per label
   */
  static Map<String, UUID> labels(Observation observation) {
    Map<String, UUID> players = new LinkedHashMap<>();
    observation.inputs().keySet().forEach(id -> players.put("player_" + (players.size() + 1), id));
    return players;
  }

  static ObjectNode state(Observation observation, Map<String, UUID> players) {
    ObjectNode state = TrackingJson.object();
    state.set("puzzle", puzzle(observation.episode()));
    state.put("time_without_team_progress", observation.timeWithoutProgress());
    state.put(
        "support_received",
        observation.hintOpened() ? "The team opened a hint for this puzzle." : "none");
    ObjectNode playerNodes = state.putObject("players");
    players.forEach(
        (label, id) -> {
          ArrayNode inputs = playerNodes.putObject(label).putArray("inputs");
          observation.inputs().get(id).forEach(input -> inputs.add(input(input)));
        });
    return state;
  }

  static ObjectNode questions(Map<String, UUID> players) {
    ObjectNode questions = TrackingJson.object();
    ObjectNode stuck = questions.putObject(STUCK);
    stuck.put("type", "noul");
    stuck.put(
        "instructions",
        "Do the failed inputs in `players` show that the team is stuck on `puzzle`?");
    ObjectNode criteria = stuck.putObject("criteria");
    criteria.put(
        "true",
        "Stuck: the team enters the same failed input again, enters guesses that ignore the clues"
            + " described in `puzzle` (such as 1234 or admin), or keeps retrying the same thing"
            + " without a new approach.");
    criteria.put(
        "false",
        "Not stuck: the team has made only a few failed inputs, or its inputs change in a way that"
            + " suggests it is working with the clues described in `puzzle`.");
    players
        .keySet()
        .forEach(
            label -> {
              ObjectNode risk = questions.putObject(riskKey(label));
              risk.put("type", "score");
              risk.put(
                  "instructions",
                  "How high is the frustration risk shown by the observable behavior of `players."
                      + label
                      + "` while working on `puzzle`? Consider `time_without_team_progress`.");
              risk.putArray("criteria")
                  .add(
                      "Low: the player works steadily, varies inputs purposefully, or has made few"
                          + " failed inputs.")
                  .add(
                      "Medium: the player has several failed inputs over some time but still tries"
                          + " different approaches.")
                  .add(
                      "High: the player repeats the same failed inputs, enters rapid guesses, or"
                          + " keeps failing for a long time without a new approach.");
            });
    return questions;
  }

  static Judgment parse(JsonNode answers, Map<String, UUID> players) {
    Map<UUID, FrustrationRisk> risks = new LinkedHashMap<>();
    players.forEach((label, id) -> risks.put(id, risk(number(answers, riskKey(label), "score"))));
    return new Judgment(number(answers, STUCK, "noul"), Map.copyOf(risks));
  }

  /**
   * Rounds the probability-weighted Score to its nearest level.
   *
   * @param score Score answer between 0 and 2
   * @return nearest risk level
   */
  static FrustrationRisk risk(double score) {
    List<FrustrationRisk> levels =
        List.of(FrustrationRisk.LOW, FrustrationRisk.MEDIUM, FrustrationRisk.HIGH);
    return levels.get((int) Math.max(0, Math.min(levels.size() - 1, Math.round(score))));
  }

  private static double number(JsonNode answers, String question, String field) {
    JsonNode value = answers.path(question).path(field);
    if (!value.isNumber()) {
      throw new IllegalStateException("Jev answer " + question + "." + field + " is missing");
    }
    return value.asDouble();
  }

  private static String riskKey(String label) {
    return "risk_" + label;
  }

  private static ObjectNode input(Input input) {
    ObjectNode node = TrackingJson.object();
    node.put("target", input.objectId());
    if (input.kind().equals("attempt")) {
      node.put("input", input.value());
      node.put("result", "wrong");
    } else {
      node.put("action", input.value());
      node.put("result", "no progress");
    }
    node.put("repeats_earlier_input", input.repeated());
    node.put("timing", input.quick() ? "quick" : "after a pause");
    return node;
  }

  private static ObjectNode puzzle(LastHourEpisode episode) {
    ObjectNode puzzle = TrackingJson.object();
    switch (episode) {
      case E1 ->
          puzzle
              .put("goal", "Restore power to the computer.")
              .put(
                  "how",
                  "The computer has no power. A hidden switch somewhere in the first room turns the"
                      + " power on.")
              .put("inputs", "interactions with objects");
      case E2 ->
          puzzle
              .put("goal", "Log in to the scientist's computer.")
              .put(
                  "how",
                  "The e-mail address and the password are split across a profile and two notes in"
                      + " the first room.")
              .put("inputs", "e-mail and password login attempts, separated by a line break");
      case E3 ->
          puzzle
              .put("goal", "Open the storage room keypad.")
              .put(
                  "how",
                  "Decode the recovery messages (binary, hex, ASCII), open the PDF, and use the"
                      + " Morse table to get a 4-digit code.")
              .put(
                  "inputs",
                  "recovery codes in the computer browser and 4-digit codes on the storage keypad");
      case E4 ->
          puzzle
              .put("goal", "Insert the correct USB stick into the computer in the second room.")
              .put(
                  "how",
                  "The initials of a note in the second room spell a color; the stick of that color"
                      + " is in a trash can.")
              .put("inputs", "colors of the inserted USB sticks");
      case E5 ->
          puzzle
              .put("goal", "Activate the ventilation.")
              .put(
                  "how",
                  "Read the files on the blue USB stick, inspect the real vent in the room, and"
                      + " enter its serial number in the computer control panel.")
              .put("inputs", "serial numbers entered in the control panel");
      case E6 ->
          puzzle
              .put("goal", "Open the exit door.")
              .put(
                  "how",
                  "Collect four paper fragments, assemble the picture, and enter the code it shows"
                      + " in the computer control panel.")
              .put("inputs", "exit codes entered in the control panel");
    }
    return puzzle;
  }
}
