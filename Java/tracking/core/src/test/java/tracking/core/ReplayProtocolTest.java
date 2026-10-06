package tracking.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Contract and scoped authentication tests for replay answers. */
class ReplayProtocolTest {
  private static final String KEY = "a-test-secret-that-is-long-enough-for-HMAC";

  @Test
  void ticketRoundTripAndTampering() {
    var plan = plan("NONE");
    var ticket = ReplayProtocol.sign(plan, KEY);
    assertEquals(plan, ReplayProtocol.verify(ticket, KEY));
    assertThrows(IllegalArgumentException.class, () -> ReplayProtocol.verify(ticket, KEY + "x"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReplayProtocol.verify(
                new ReplayProtocol.Ticket(
                    ticket.planJson().replace("P001", "P002"), ticket.signature()),
                KEY));
    assertThrows(IllegalArgumentException.class, () -> ReplayProtocol.sign(plan, "short"));
  }

  @Test
  void strictJsonRejectsUnknownAndDuplicateFields() {
    assertThrows(
        RuntimeException.class,
        () ->
            ReplayProtocol.JSON.readValue(
                "{\"planJson\":\"a\",\"signature\":\"b\",\"video\":\"secret\"}",
                ReplayProtocol.Ticket.class));
    assertThrows(
        RuntimeException.class,
        () ->
            ReplayProtocol.JSON.readValue(
                "{\"planJson\":\"a\",\"planJson\":\"b\",\"signature\":\"b\"}",
                ReplayProtocol.Ticket.class));
  }

  @Test
  void baselineThenInterventionIsRequiredAndNonobservableIsAllowed() {
    var plan = plan("HINT_OFFER");
    var partial = responses(new ReplayProtocol.Baseline(1, 2, 3, 4, "HINT"), null);
    assertDoesNotThrow(() -> ReplayProtocol.validateResponses(plan, partial, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReplayProtocol.validateResponses(plan, partial, true));
    var completed =
        responses(
            partial.answers().getFirst().baseline(),
            new ReplayProtocol.Intervention(false, null, null, null, null));
    assertDoesNotThrow(() -> ReplayProtocol.validateResponses(plan, completed, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReplayProtocol.validateResponses(plan("NONE"), completed, true));
  }

  @Test
  void ratingsAndEpisodeSpecificSupportAreValidated() {
    for (int rating : List.of(0, 6)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ReplayProtocol.validateResponses(
                  plan("NONE"),
                  responses(new ReplayProtocol.Baseline(rating, 1, 1, 1, "HINT"), null),
                  true));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReplayProtocol.validateResponses(
                plan("NONE"),
                responses(new ReplayProtocol.Baseline(1, 1, 1, 1, "SIMPLIFY"), null),
                true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ReplayProtocol.validateResponses(
                plan("HINT_OFFER"),
                responses(
                    new ReplayProtocol.Baseline(1, 1, 1, 1, "HINT"),
                    new ReplayProtocol.Intervention(false, "EARLY", null, null, null)),
                true));
  }

  @Test
  void everyLastHourEpisodeIsAccepted() {
    assertDoesNotThrow(() -> ReplayProtocol.validatePlan(plan("NONE", "E6")));
    assertThrows(
        IllegalArgumentException.class, () -> ReplayProtocol.validatePlan(plan("NONE", "E7")));
  }

  private static ReplayProtocol.Responses responses(
      ReplayProtocol.Baseline baseline, ReplayProtocol.Intervention intervention) {
    return new ReplayProtocol.Responses(
        List.of(new ReplayProtocol.Answer("S1", baseline, intervention)), "", "");
  }

  private static ReplayProtocol.Plan plan(String action) {
    return plan(action, "E1");
  }

  private static ReplayProtocol.Plan plan(String action, String episode) {
    return new ReplayProtocol.Plan(
        1,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "P001",
        UUID.randomUUID().toString(),
        100,
        200,
        List.of(
            new ReplayProtocol.Clip(
                "S1",
                3,
                episode,
                "LOW",
                action,
                1000,
                0,
                1000,
                action.equals("NONE") ? 1000 : 2000)));
  }
}
