package rooms.lasthour.adaptation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.FrustrationRisk;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Input;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Observation;
import tracking.core.TrackingJson;

class LastHourJevTest {
  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();

  @Test
  void requestCarriesCodeComputedFactsAndOneRiskQuestionPerPerson() {
    Map<UUID, List<Input>> inputs = new LinkedHashMap<>();
    inputs.put(first, List.of(new Input("attempt", "storage-keypad", "1234", true, true)));
    inputs.put(second, List.of(new Input("action", "pc-main", "power-button", false, false)));
    var observation = new Observation(LastHourEpisode.E3, 0, "2 to 5 minutes", true, inputs);
    var players = LastHourJev.labels(observation);

    var state = LastHourJev.state(observation, players);
    var questions = LastHourJev.questions(players);

    assertEquals(Map.of("player_1", first, "player_2", second), players);
    assertEquals("2 to 5 minutes", state.path("time_without_team_progress").asString());
    var attempt = state.path("players").path("player_1").path("inputs").path(0);
    assertEquals("1234", attempt.path("input").asString());
    assertTrue(attempt.path("repeats_earlier_input").asBoolean());
    assertEquals("quick", attempt.path("timing").asString());
    assertEquals(
        "power-button",
        state.path("players").path("player_2").path("inputs").path(0).path("action").asString());
    assertEquals(
        List.of("team_stuck", "risk_player_1", "risk_player_2"),
        List.copyOf(questions.propertyNames()));
  }

  @Test
  void answersMapToTypedJudgmentOrFail() {
    var players = Map.of("player_1", first, "player_2", second);
    var answers =
        TrackingJson.object(
            """
            {"team_stuck": {"type": "noul", "noul": 0.82},
             "risk_player_1": {"type": "score", "score": 1.97},
             "risk_player_2": {"type": "score", "score": 0.49}}
            """);

    var judgment = LastHourJev.parse(answers, players);

    assertEquals(0.82, judgment.stuckProbability());
    assertEquals(
        Map.of(first, FrustrationRisk.HIGH, second, FrustrationRisk.LOW), judgment.risks());
    assertEquals(FrustrationRisk.MEDIUM, LastHourJev.risk(0.5));
    answers.remove("risk_player_2");
    assertThrows(IllegalStateException.class, () -> LastHourJev.parse(answers, players));
  }

  @Test
  void missingApiKeyFailsTheRequestInsteadOfGuessing() {
    var observation =
        new Observation(
            LastHourEpisode.E1,
            0,
            "less than 1 minute",
            false,
            Map.of(first, List.of(new Input("action", "pc-main", "power-button", false, false))));

    assertTrue(new LastHourJev(null, "jev-1.13.0").judge(observation).isCompletedExceptionally());
  }
}
