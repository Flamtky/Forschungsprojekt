package rooms.lasthour.adaptation;

import engine.Game;
import engine.network.server.Session;
import engine.tracking.Tracking;
import engine.utils.logging.DungeonLogger;
import feature.hud.dialogs.DialogFactory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Action;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.FrustrationRisk;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.Observation;
import rooms.lasthour.adaptation.LastHourAdaptationPolicy.SupportState;
import rooms.lasthour.recording.LastHourRecordingProtocol;
import rooms.lasthour.recording.LastHourRecordingProtocol.SyncPoint;
import rooms.lasthour.util.LastHourPuzzle;
import rooms.lasthour.util.LastHourQuestLogUtil;
import tracking.core.TrackingJson;

/** Server-authoritative facade for the Last Hour pilot adaptation. */
public final class LastHourAdaptation {
  private static final String STATE_EVENT = "adaptation.state";
  private static final String RISK_EVENT = "adaptation.risk";
  private static final String DECISION_EVENT = "adaptation.decision";
  private static final String INTERVENTION_EVENT = "adaptation.intervention";
  private static final String JUDGMENT_EVENT = "adaptation.judgment";
  private static final String JUDGMENT_FAILED_EVENT = "adaptation.judgment-failed";

  private static final String STORAGE_SIMPLIFY_MESSAGE =
      "The storage keypad now shows two of the four digits.";
  private static final String STORAGE_AUTO_COMPLETE_MESSAGE =
      "The storage access will now be unlocked.";

  /** Wait before asking again about an observation whose Jev request failed. */
  private static final long RETRY_SECONDS = 15;

  private static final DungeonLogger LOGGER = DungeonLogger.getLogger(LastHourAdaptation.class);

  record HintResponse(boolean accepted, String reason) {}

  private record EligibleRecipient(int entityId, UUID participantId) {}

  private record PendingJudgment(
      Observation observation, CompletableFuture<LastHourJev.Result> result) {}

  /** Concrete E3 effects supplied by the level. */
  public interface StorageInterventions {
    /** Shows the fixed keypad digits without changing its code. */
    void simplify();

    /** Runs the level's idempotent storage success path. */
    void autoComplete();
  }

  private static LastHourAdaptation active;

  private final LastHourAdaptationPolicy policy;
  private final LastHourJev jev;
  private final StorageInterventions storageInterventions;
  private final LongSupplier nowSeconds;
  private final Map<LastHourEpisode, SupportState> lastStates =
      new EnumMap<>(LastHourEpisode.class);
  private final Map<LastHourEpisode, Map<UUID, FrustrationRisk>> lastRisks =
      new EnumMap<>(LastHourEpisode.class);
  private final Set<UUID> recordingSyncedParticipants = new HashSet<>();
  private final Map<UUID, Session> recordingConnections = new HashMap<>();
  private final Map<UUID, UUID> visibleRecordingIds = new HashMap<>();
  private final Set<LastHourEpisode> settledHintOffers = EnumSet.noneOf(LastHourEpisode.class);
  private final Set<LastHourEpisode> completedEpisodes = EnumSet.noneOf(LastHourEpisode.class);
  private final Map<LastHourEpisode, PendingJudgment> pendingJudgments =
      new EnumMap<>(LastHourEpisode.class);
  private final Map<LastHourEpisode, Observation> requestedObservations =
      new EnumMap<>(LastHourEpisode.class);
  private final Map<LastHourEpisode, Long> retryAfter = new EnumMap<>(LastHourEpisode.class);

  private LastHourAdaptation(
      LastHourAdaptationPolicy policy,
      LastHourJev jev,
      StorageInterventions storageInterventions,
      LongSupplier nowSeconds) {
    this.policy = policy;
    this.jev = jev;
    this.storageInterventions = storageInterventions;
    this.nowSeconds = nowSeconds;
    for (LastHourEpisode episode : LastHourEpisode.values()) {
      lastStates.put(episode, SupportState.B0);
      lastRisks.put(episode, new HashMap<>());
    }
  }

  /**
   * Starts a fresh adaptation lifecycle for one authoritative Last Hour level.
   *
   * @param storageInterventions concrete E3 effects
   */
  public static void install(StorageInterventions storageInterventions) {
    if (Game.isMultiplayerClient()) {
      active = null;
      return;
    }
    LastHourJev jev = LastHourJev.fromEnvironment();
    if (!jev.configured()) {
      LOGGER.error("TYPESAFE_API_KEY is not set: Jev judgments fail and no support is offered.");
    }
    active =
        new LastHourAdaptation(
            new LastHourAdaptationPolicy(
                LastHourAdaptationPolicy.Thresholds.fromSystemProperties()),
            jev,
            storageInterventions,
            () -> System.nanoTime() / 1_000_000_000L);
  }

  /** Runs the time-based policy check from the existing level tick. */
  public static void tick() {
    if (active == null || Game.isMultiplayerClient()) {
      return;
    }
    active.evaluate();
  }

  /**
   * Records an episode start for the policy.
   *
   * @param puzzle started puzzle
   */
  public static void started(LastHourPuzzle puzzle) {
    if (active != null) {
      active.policy.started(puzzle, active.nowSeconds.getAsLong());
    }
  }

  /**
   * Records one server-observed action that may become a repetition signal.
   *
   * @param puzzle related puzzle
   * @param objectId stable object identifier
   * @param actionValue stable action value
   * @param participantId acting participant
   */
  public static void action(
      LastHourPuzzle puzzle, String objectId, String actionValue, UUID participantId) {
    if (active != null) {
      active.policy.action(
          puzzle, objectId, actionValue, participantId, active.nowSeconds.getAsLong());
    }
  }

  /**
   * Records a server-evaluated attempt for the policy.
   *
   * @param puzzle related puzzle
   * @param objectId stable object identifier
   * @param rawAnswer submitted answer
   * @param correct server-evaluated correctness
   * @param participantId acting participant
   */
  public static void attempt(
      LastHourPuzzle puzzle,
      String objectId,
      String rawAnswer,
      boolean correct,
      UUID participantId) {
    if (active != null) {
      active.policy.attempt(
          puzzle, objectId, rawAnswer, correct, participantId, active.nowSeconds.getAsLong());
    }
  }

  /**
   * Records puzzle progress or terminal completion.
   *
   * @param puzzle solved puzzle
   */
  public static void solved(LastHourPuzzle puzzle) {
    if (active != null) {
      active.policy.solved(puzzle, active.nowSeconds.getAsLong());
    }
  }

  /**
   * Attempts one marker per connection immediately before that player's intro is shown.
   *
   * @param participantId participant whose recording should align to the marker
   * @param connection current client connection, or null for singleplayer
   * @return whether the marker was persisted or had already been persisted
   */
  public static boolean recordingSync(UUID participantId, Session connection) {
    if (active == null) {
      return false;
    }
    beginRecordingConnection(
        active.recordingConnections,
        active.recordingSyncedParticipants,
        active.visibleRecordingIds,
        participantId,
        connection);
    return recordingSyncOnce(
        active.recordingSyncedParticipants,
        participantId,
        id -> {
          var payload = basePayload(LastHourEpisode.E1);
          payload.put("marker", "intro-cutscene-shown");
          return Tracking.roomEvent(
                  LastHourPuzzle.POWER.id(), LastHourRecordingProtocol.INTRO_MARKER, payload, id)
              .isPresent();
        });
  }

  /**
   * Persists the first encoded client frame containing the intro and its local recording clock.
   *
   * @param participantId participant whose client sent the acknowledgement
   * @param point validated recording metadata
   * @return whether the visible sync event was persisted or had already been persisted
   */
  public static boolean recordingSyncVisible(UUID participantId, SyncPoint point) {
    if (active == null) {
      return false;
    }
    return recordingSyncVisibleOnce(
        active.visibleRecordingIds,
        participantId,
        point.recordingId(),
        id -> {
          var payload = basePayload(LastHourEpisode.E1);
          payload.put("marker", "intro-cutscene-captured");
          payload.put("recordingId", point.recordingId().toString());
          payload.put("frameIndex", point.frameIndex());
          payload.put("videoSyncMs", point.videoSyncMs());
          payload.put("framesPerSecond", point.framesPerSecond());
          payload.put("width", point.width());
          payload.put("height", point.height());
          payload.put("format", LastHourRecordingProtocol.FORMAT);
          return Tracking.roomEvent(
                  LastHourPuzzle.POWER.id(),
                  LastHourRecordingProtocol.VISIBLE_SYNC_EVENT,
                  payload,
                  id)
              .isPresent();
        });
  }

  /**
   * Returns the recording identity whose visible intro marker was persisted for a participant.
   *
   * @param participantId server-side participant identity
   * @return persisted recording identity, if the tracking write succeeded
   */
  public static java.util.Optional<UUID> visibleRecordingId(UUID participantId) {
    if (active == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.ofNullable(active.visibleRecordingIds.get(participantId));
  }

  private void evaluate() {
    long now = nowSeconds.getAsLong();
    int[] targets = playerTargets();
    Set<UUID> participants = mappedParticipants();
    for (LastHourEpisode episode : LastHourEpisode.values()) {
      if (policy.active(episode)) {
        participants.forEach(participantId -> policy.registerParticipant(episode, participantId));
      }
      collectJudgment(episode, now);
      requestJudgment(episode, now);
      SupportState current = policy.supportState(episode, now);
      SupportState previous = lastStates.put(episode, current);
      if (current != previous) {
        var payload = basePayload(episode);
        payload.put("previousState", previous.name());
        payload.put("state", current.name());
        payload.put("reason", policy.active(episode) ? stateReason(current) : "episode-completed");
        Tracking.roomEvent(episode.trackingPuzzle().id(), STATE_EVENT, payload);
      }

      Map<UUID, FrustrationRisk> currentRisks = policy.risks(episode);
      // A started episode that is no longer active was just completed. Its end is logged once for
      // everyone, even when LOW is unchanged, so replay LOW phases never outlast the episode.
      boolean completedNow =
          !currentRisks.isEmpty() && !policy.active(episode) && completedEpisodes.add(episode);
      Map<UUID, FrustrationRisk> priorRisks = lastRisks.get(episode);
      priorRisks.keySet().retainAll(participants);
      currentRisks.forEach(
          (participantId, risk) -> {
            if (!participants.contains(participantId)) {
              return;
            }
            FrustrationRisk previousRisk = priorRisks.put(participantId, risk);
            if (previousRisk != risk || completedNow) {
              var payload = basePayload(episode);
              payload.put("risk", risk.name());
              payload.put("reason", riskReason(episode, risk));
              Tracking.roomEvent(episode.trackingPuzzle().id(), RISK_EVENT, payload, participantId);
            }
          });

      policy
          .nextDecision(episode, now, targets.length > 0)
          .ifPresent(decision -> apply(decision, targets));
    }
  }

  /**
   * Applies a finished Jev request on the game thread; failures leave the episode unjudged.
   *
   * @param episode episode whose request may have finished
   * @param now current policy time in seconds
   */
  private void collectJudgment(LastHourEpisode episode, long now) {
    PendingJudgment pending = pendingJudgments.get(episode);
    if (pending == null || !pending.result().isDone()) {
      return;
    }
    pendingJudgments.remove(episode);
    LastHourJev.Result result;
    try {
      result = pending.result().join();
    } catch (CompletionException | CancellationException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      requestedObservations.remove(episode);
      retryAfter.put(episode, now + RETRY_SECONDS);
      LOGGER.warn("Jev judgment failed for " + episode + ": " + cause);
      var payload = basePayload(episode);
      payload.put("error", String.valueOf(cause));
      Tracking.roomEvent(episode.trackingPuzzle().id(), JUDGMENT_FAILED_EVENT, payload);
      return;
    }
    boolean applied = policy.judged(pending.observation(), result.judgment(), now);
    var payload = basePayload(episode);
    payload.put("model", result.model());
    payload.put("latencyMs", result.latencyMs());
    payload.put("applied", applied);
    payload.put("stuckProbability", result.judgment().stuckProbability());
    var players = payload.putObject("players");
    result
        .players()
        .forEach(
            (label, participantId) -> {
              var player = players.putObject(label);
              player.put("participantId", participantId.toString());
              player.put("risk", result.judgment().risks().get(participantId).name());
            });
    payload.set("state", result.state());
    payload.set("answers", result.answers());
    Tracking.roomEvent(episode.trackingPuzzle().id(), JUDGMENT_EVENT, payload);
  }

  /**
   * Asks Jev once per changed observation; unchanged situations are not judged again.
   *
   * @param episode episode to observe
   * @param now current policy time in seconds
   */
  private void requestJudgment(LastHourEpisode episode, long now) {
    if (pendingJudgments.containsKey(episode)
        || now < retryAfter.getOrDefault(episode, Long.MIN_VALUE)) {
      return;
    }
    policy
        .observation(episode, now)
        .filter(observation -> !observation.equals(requestedObservations.get(episode)))
        .ifPresent(
            observation -> {
              requestedObservations.put(episode, observation);
              pendingJudgments.put(
                  episode, new PendingJudgment(observation, jev.judge(observation)));
            });
  }

  private String riskReason(LastHourEpisode episode, FrustrationRisk risk) {
    if (!policy.active(episode)) {
      return "episode-completed";
    }
    return risk == FrustrationRisk.NONE ? "no-current-judgment" : "jev-judgment";
  }

  private void apply(LastHourAdaptationPolicy.Decision decision, int[] targets) {
    var payload = basePayload(decision.episode());
    payload.put("action", decision.action().name());
    payload.put("state", decision.state().name());
    payload.put("reason", decision.reason());
    Tracking.roomEvent(decision.episode().trackingPuzzle().id(), DECISION_EVENT, payload);

    switch (decision.action()) {
      case HINT_OFFER -> showHintOffer(decision.episode(), targets);
      case STORAGE_SIMPLIFY -> {
        storageInterventions.simplify();
        showToEach(STORAGE_SIMPLIFY_MESSAGE, "Support", targets);
        LastHourQuestLogUtil.addSupportEntry(STORAGE_SIMPLIFY_MESSAGE);
        intervention(decision.episode(), decision.action(), decision.reason());
      }
      case STORAGE_AUTO_COMPLETE -> {
        showToEach(STORAGE_AUTO_COMPLETE_MESSAGE, "Support", targets);
        LastHourQuestLogUtil.addSupportEntry(STORAGE_AUTO_COMPLETE_MESSAGE);
        storageInterventions.autoComplete();
        intervention(decision.episode(), decision.action(), decision.reason());
      }
      case NONE -> {
        // NONE is never returned as a decision.
      }
    }
  }

  private void showHintOffer(LastHourEpisode episode, int[] targets) {
    // ESC must not count as rejecting the hint offer.
    DialogFactory.showYesNoDialog(
        "Your team has made no progress for a while. Open a hint?",
        "Hint offer",
        () -> settleHintOffer(episode, true),
        () -> settleHintOffer(episode, false),
        false,
        targets);
    intervention(episode, Action.HINT_OFFER, "offered-to-team");
  }

  private void settleHintOffer(LastHourEpisode episode, boolean requestedAcceptance) {
    if (!settleOnce(settledHintOffers, episode)) {
      return;
    }
    if (!requestedAcceptance) {
      emitHintResponse(episode, resolveHintResponse(false, false, false));
      return;
    }

    List<EligibleRecipient> recipients = currentEligibleRecipients();
    if (recipients.isEmpty()) {
      emitHintResponse(episode, resolveHintResponse(true, false, false));
      return;
    }

    HintResponse response =
        resolveHintResponse(true, true, policy.hintAccepted(episode, nowSeconds.getAsLong()));
    if (!response.accepted()) {
      emitHintResponse(episode, response);
      return;
    }

    int[] targets = recipients.stream().mapToInt(EligibleRecipient::entityId).toArray();
    showToEach(hint(episode), "Hint", targets);
    LastHourQuestLogUtil.addSupportEntry(hint(episode));
    String hintId = adaptiveHintId(episode);
    recipients.forEach(
        recipient ->
            Tracking.hintUsed(episode.trackingPuzzle().id(), hintId, recipient.participantId()));
    emitHintResponse(episode, response);
  }

  /**
   * Shows a message to every target in a separate dialog, so one player's OK closes only their own
   * copy and the other can keep reading.
   *
   * @param text message body
   * @param title dialog title
   * @param targets player entity IDs
   */
  private static void showToEach(String text, String title, int[] targets) {
    for (int target : targets) {
      DialogFactory.showOkDialog(text, title, () -> {}, target);
    }
  }

  private void intervention(LastHourEpisode episode, Action action, String reason) {
    var payload = basePayload(episode);
    payload.put("action", action.name());
    payload.put("reason", reason);
    Tracking.roomEvent(episode.trackingPuzzle().id(), INTERVENTION_EVENT, payload);
  }

  private static tools.jackson.databind.node.ObjectNode basePayload(LastHourEpisode episode) {
    return TrackingJson.object().put("episode", episode.name());
  }

  private static String stateReason(SupportState state) {
    return switch (state) {
      case B0 -> "no-current-blockage";
      case B1 -> "time-without-stuck-judgment";
      case B2 -> "time-and-stuck-judgment";
      case B3 -> "continued-blockage-after-support";
    };
  }

  private static int[] playerTargets() {
    return Game.allPlayers().mapToInt(player -> player.id()).toArray();
  }

  private static List<EligibleRecipient> currentEligibleRecipients() {
    List<EligibleRecipient> recipients = new ArrayList<>();
    Game.allPlayers()
        .forEach(
            player ->
                Tracking.participantForEntity(player.id())
                    .ifPresent(
                        participantId ->
                            recipients.add(new EligibleRecipient(player.id(), participantId))));
    return recipients;
  }

  private Set<UUID> mappedParticipants() {
    Set<UUID> participants = new HashSet<>();
    Game.allPlayers()
        .forEach(
            player ->
                Tracking.participantForEntity(player.id())
                    .filter(recordingSyncedParticipants::contains)
                    .ifPresent(participants::add));
    return participants;
  }

  private static String hint(LastHourEpisode episode) {
    return switch (episode) {
      case E1 -> "Look for the hidden switch in the first room. Check under the paper pile.";
      case E2 -> "Find the profile and both notes. Combine the email address and password parts.";
      case E3 ->
          "Compare senders and domains, then connect the converted code, PDF, and Morse table.";
      case E4 -> "Read the note in the second room. Its initials point to the blue trash can.";
      case E5 -> "Open the blue USB files, inspect the real vent, and use its serial number.";
      case E6 -> "Collect four fragments, assemble the image, enter its code, and open the door.";
    };
  }

  static String adaptiveHintId(LastHourEpisode episode) {
    return "adaptive-hint-" + episode.name().toLowerCase();
  }

  static boolean recordingSyncOnce(
      Set<UUID> syncedParticipants, UUID participantId, Predicate<UUID> emitter) {
    if (syncedParticipants.contains(participantId)) {
      return true;
    }
    if (!emitter.test(participantId)) {
      return false;
    }
    syncedParticipants.add(participantId);
    return true;
  }

  static void beginRecordingConnection(
      Map<UUID, Session> connections,
      Set<UUID> syncedParticipants,
      Map<UUID, UUID> recordingIds,
      UUID participantId,
      Session connection) {
    if (connections.containsKey(participantId) && connections.get(participantId) == connection) {
      return;
    }
    connections.put(participantId, connection);
    syncedParticipants.remove(participantId);
    recordingIds.remove(participantId);
  }

  static boolean recordingSyncVisibleOnce(
      Map<UUID, UUID> recordingIds, UUID participantId, UUID recordingId, Predicate<UUID> emitter) {
    UUID persistedRecordingId = recordingIds.get(participantId);
    if (recordingId.equals(persistedRecordingId)) {
      return true;
    }
    if (!emitter.test(participantId)) {
      return false;
    }
    recordingIds.put(participantId, recordingId);
    return true;
  }

  static boolean settleOnce(Set<LastHourEpisode> settledOffers, LastHourEpisode episode) {
    return settledOffers.add(episode);
  }

  static HintResponse resolveHintResponse(
      boolean requestedAcceptance, boolean hasEligibleRecipients, boolean policyAccepted) {
    if (!requestedAcceptance) {
      return new HintResponse(false, "team-rejected-hint");
    }
    if (!hasEligibleRecipients) {
      return new HintResponse(false, "no-eligible-recipients-at-response");
    }
    if (!policyAccepted) {
      return new HintResponse(false, "episode-completed-before-response");
    }
    return new HintResponse(true, "team-opened-hint");
  }

  private void emitHintResponse(LastHourEpisode episode, HintResponse response) {
    var payload = basePayload(episode);
    payload.put("action", Action.HINT_OFFER.name());
    payload.put("hintId", adaptiveHintId(episode));
    payload.put("accepted", response.accepted());
    payload.put("reason", response.reason());
    Tracking.roomEvent(episode.trackingPuzzle().id(), INTERVENTION_EVENT, payload);
  }
}
