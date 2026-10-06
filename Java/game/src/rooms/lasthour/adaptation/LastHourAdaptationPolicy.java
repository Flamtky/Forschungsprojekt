package rooms.lasthour.adaptation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import rooms.lasthour.util.LastHourPuzzle;

/**
 * Episode policy with code-owned guardrails. Time without progress and the escalation ladder stay
 * deterministic; whether the team is stuck and each person's frustration risk come from Jev
 * judgments over the inputs observed since the last progress. Risk labels describe observed play,
 * not player emotions.
 */
final class LastHourAdaptationPolicy {

  /** Inputs of one person within this many seconds of their previous input count as quick. */
  static final long QUICK_INPUT_SECONDS = 15;

  /** Jev sees at most this many recent inputs per person, which keeps the state small. */
  static final int MAX_INPUTS_PER_PERSON = 8;

  /**
   * A person's input equal to their previous one within this many seconds is ignored. Hammering
   * confirm with the same code is one attempt, not new evidence that the team is stuck.
   */
  static final long DUPLICATE_INPUT_SECONDS = 5;

  enum SupportState {
    B0,
    B1,
    B2,
    B3
  }

  enum FrustrationRisk {
    LOW,
    MEDIUM,
    HIGH,
    /** No Jev judgment exists in the current observation phase. */
    NONE
  }

  enum Action {
    NONE,
    HINT_OFFER,
    STORAGE_SIMPLIFY,
    STORAGE_AUTO_COMPLETE
  }

  record Thresholds(long stallSeconds, long persistentSeconds, double stuckProbability) {

    Thresholds {
      if (stallSeconds < 1
          || persistentSeconds < 1
          || !(stuckProbability > 0 && stuckProbability < 1)) {
        throw new IllegalArgumentException("Adaptation thresholds are out of range");
      }
    }

    static Thresholds fromSystemProperties() {
      return new Thresholds(
          positiveLong("lasthour.adaptation.stallSeconds", 300),
          positiveLong("lasthour.adaptation.persistentSeconds", 180),
          probability("lasthour.adaptation.stuckProbability", 0.5));
    }

    private static long positiveLong(String property, long defaultValue) {
      try {
        return Math.max(
            1, Long.parseLong(System.getProperty(property, String.valueOf(defaultValue))));
      } catch (NumberFormatException ignored) {
        return defaultValue;
      }
    }

    private static double probability(String property, double defaultValue) {
      try {
        double value =
            Double.parseDouble(System.getProperty(property, String.valueOf(defaultValue)));
        return value > 0 && value < 1 ? value : defaultValue;
      } catch (NumberFormatException ignored) {
        return defaultValue;
      }
    }
  }

  record Decision(LastHourEpisode episode, Action action, SupportState state, String reason) {}

  /**
   * One failed answer or progress-free interaction since the episode's last progress.
   *
   * @param kind {@code attempt} for a wrong answer, {@code action} for an interaction
   * @param objectId stable object identifier
   * @param value raw answer or stable action value
   * @param repeated whether anyone in the team already sent the same input in this phase
   * @param quick whether the same person sent it shortly after their previous input
   */
  record Input(String kind, String objectId, String value, boolean repeated, boolean quick) {}

  /**
   * Everything Jev judges for one episode. An unchanged observation needs no new judgment, and a
   * judgment only applies while its observation is still current.
   *
   * @param episode observed episode
   * @param phase counts observation resets, so a new phase never equals an earlier one
   * @param timeWithoutProgress code-computed time bucket, because Jev does not compare durations
   * @param hintOpened whether the team opened a hint in the current phase
   * @param inputs recent inputs per participant in registration order, including people without
   *     inputs
   */
  record Observation(
      LastHourEpisode episode,
      int phase,
      String timeWithoutProgress,
      boolean hintOpened,
      Map<UUID, List<Input>> inputs) {}

  /**
   * Typed Jev answers for one observation.
   *
   * @param stuckProbability Noul probability that the team is stuck
   * @param risks frustration risk per participant
   */
  record Judgment(double stuckProbability, Map<UUID, FrustrationRisk> risks) {}

  private record TimedInput(UUID participantId, long seconds, Input input) {}

  private static final class EpisodeState {
    private boolean started;
    private boolean solved;
    private long lastProgressSeconds;
    private final List<TimedInput> inputs = new ArrayList<>();
    private int phase;
    private Judgment judgment;
    private Map<UUID, List<Input>> judgedInputs;
    private Action lastAction = Action.NONE;
    private boolean hintAccepted;
    private final Set<UUID> participants = new LinkedHashSet<>();
  }

  private final Thresholds thresholds;
  private final Map<LastHourEpisode, EpisodeState> states = new EnumMap<>(LastHourEpisode.class);

  /** Last real progress in any episode; support does not advance this timestamp. */
  private long lastRoomProgressSeconds = Long.MIN_VALUE;

  LastHourAdaptationPolicy(Thresholds thresholds) {
    this.thresholds = thresholds;
    for (LastHourEpisode episode : LastHourEpisode.values()) {
      states.put(episode, new EpisodeState());
    }
  }

  void started(LastHourPuzzle puzzle, long nowSeconds) {
    LastHourEpisode.forPuzzle(puzzle)
        .ifPresent(
            episode -> {
              EpisodeState state = states.get(episode);
              if (!state.started && !state.solved) {
                state.started = true;
                state.lastProgressSeconds = nowSeconds;
                if (lastRoomProgressSeconds == Long.MIN_VALUE) {
                  lastRoomProgressSeconds = nowSeconds;
                }
              }
            });
  }

  void action(
      LastHourPuzzle puzzle,
      String objectId,
      String actionValue,
      UUID participantId,
      long nowSeconds) {
    LastHourEpisode.forPuzzle(puzzle)
        .ifPresent(
            episode ->
                record(
                    states.get(episode),
                    participantId,
                    "action",
                    objectId,
                    actionValue,
                    nowSeconds));
  }

  void attempt(
      LastHourPuzzle puzzle,
      String objectId,
      String rawAnswer,
      boolean correct,
      UUID participantId,
      long nowSeconds) {
    LastHourEpisode.forPuzzle(puzzle)
        .ifPresent(
            episode -> {
              EpisodeState state = states.get(episode);
              if (correct) {
                progress(state, nowSeconds);
              } else {
                record(state, participantId, "attempt", objectId, rawAnswer, nowSeconds);
              }
            });
  }

  void solved(LastHourPuzzle puzzle, long nowSeconds) {
    LastHourEpisode.forPuzzle(puzzle)
        .ifPresent(
            episode -> {
              EpisodeState state = states.get(episode);
              progress(state, nowSeconds);
              if (episode.terminalPuzzle(puzzle)) {
                state.solved = true;
              }
            });
  }

  /**
   * Returns what Jev should judge now. Pure inactivity yields nothing: without inputs since the
   * last observation reset there is no evidence to judge and the episode cannot pass B1. The time
   * bucket uses the same room-wide progress clock as the support thresholds.
   *
   * @param episode episode to observe
   * @param nowSeconds current policy time
   * @return the current observation, or empty when there is nothing to judge
   */
  Optional<Observation> observation(LastHourEpisode episode, long nowSeconds) {
    EpisodeState state = states.get(episode);
    if (!state.started || state.solved || state.inputs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Observation(
            episode,
            state.phase,
            timeBucket(timeWithoutProgress(state, nowSeconds)),
            state.hintAccepted,
            recentInputs(state)));
  }

  /**
   * Applies a judgment if its observation is still current.
   *
   * @param observation observation the judgment answers
   * @param judgment typed Jev answers
   * @param nowSeconds current policy time
   * @return whether the judgment was applied
   */
  boolean judged(Observation observation, Judgment judgment, long nowSeconds) {
    if (!observation(observation.episode(), nowSeconds).equals(Optional.of(observation))) {
      return false;
    }
    EpisodeState state = states.get(observation.episode());
    state.judgment = judgment;
    state.judgedInputs = observation.inputs();
    return true;
  }

  /**
   * Applies the support thresholds using time without progress anywhere in the room.
   *
   * @param episode episode to evaluate
   * @param nowSeconds current policy time
   * @return current support state
   */
  SupportState supportState(LastHourEpisode episode, long nowSeconds) {
    EpisodeState state = states.get(episode);
    if (!state.started || state.solved) {
      return SupportState.B0;
    }
    long stalledFor = timeWithoutProgress(state, nowSeconds);
    long required = state.hintAccepted ? thresholds.persistentSeconds() : thresholds.stallSeconds();
    if (stalledFor < required) {
      return SupportState.B0;
    }
    if (!stuck(state)) {
      return SupportState.B1;
    }
    return state.hintAccepted ? SupportState.B3 : SupportState.B2;
  }

  /**
   * Returns each person's risk from the latest Jev judgment in the current phase. Before the first
   * judgment it is {@code NONE}; a completed episode reports {@code LOW}.
   *
   * @param episode episode to report
   * @return risk per registered participant, empty before the episode starts
   */
  Map<UUID, FrustrationRisk> risks(LastHourEpisode episode) {
    EpisodeState state = states.get(episode);
    if (!state.started) {
      return Map.of();
    }
    Map<UUID, FrustrationRisk> risks = new LinkedHashMap<>();
    FrustrationRisk fallback = state.solved ? FrustrationRisk.LOW : FrustrationRisk.NONE;
    state.participants.forEach(id -> risks.put(id, fallback));
    if (!state.solved && state.judgment != null) {
      state.judgment.risks().forEach(risks::replace);
    }
    return risks;
  }

  Optional<Decision> nextDecision(LastHourEpisode episode, long nowSeconds, boolean hasRecipients) {
    if (!hasRecipients) {
      return Optional.empty();
    }
    EpisodeState episodeState = states.get(episode);
    SupportState supportState = supportState(episode, nowSeconds);
    if (supportState == SupportState.B2 && episodeState.lastAction == Action.NONE) {
      episodeState.lastAction = Action.HINT_OFFER;
      return Optional.of(
          new Decision(episode, Action.HINT_OFFER, supportState, "time-and-stuck-judgment"));
    }
    if (episode == LastHourEpisode.E3
        && supportState == SupportState.B3
        && episodeState.lastAction == Action.HINT_OFFER
        && episodeState.hintAccepted) {
      episodeState.lastAction = Action.STORAGE_SIMPLIFY;
      resetObservation(episodeState, nowSeconds);
      return Optional.of(
          new Decision(
              episode, Action.STORAGE_SIMPLIFY, supportState, "continued-blockage-after-hint"));
    }
    if (episode == LastHourEpisode.E3
        && supportState == SupportState.B3
        && episodeState.lastAction == Action.STORAGE_SIMPLIFY) {
      episodeState.lastAction = Action.STORAGE_AUTO_COMPLETE;
      resetObservation(episodeState, nowSeconds);
      return Optional.of(
          new Decision(
              episode,
              Action.STORAGE_AUTO_COMPLETE,
              supportState,
              "continued-blockage-after-simplification"));
    }
    return Optional.empty();
  }

  boolean hintAccepted(LastHourEpisode episode, long nowSeconds) {
    EpisodeState state = states.get(episode);
    if (state.lastAction != Action.HINT_OFFER || state.hintAccepted || state.solved) {
      return false;
    }
    state.hintAccepted = true;
    resetObservation(state, nowSeconds);
    return true;
  }

  Action lastAction(LastHourEpisode episode) {
    return states.get(episode).lastAction;
  }

  void registerParticipant(LastHourEpisode episode, UUID participantId) {
    EpisodeState state = states.get(episode);
    if (state.started && !state.solved) {
      state.participants.add(participantId);
    }
  }

  boolean active(LastHourEpisode episode) {
    EpisodeState state = states.get(episode);
    return state.started && !state.solved;
  }

  /**
   * Measures time since the later of the episode's observation reset and real room progress.
   *
   * @param state episode state
   * @param nowSeconds current policy time
   * @return nonnegative time without progress in seconds
   */
  private long timeWithoutProgress(EpisodeState state, long nowSeconds) {
    return Math.max(0, nowSeconds - Math.max(state.lastProgressSeconds, lastRoomProgressSeconds));
  }

  /**
   * Maps the shared support duration to the bucket Jev sees, so Jev never compares raw durations.
   *
   * @param seconds time without progress
   * @return English duration bucket
   */
  static String timeBucket(long seconds) {
    if (seconds < 60) {
      return "less than 1 minute";
    }
    if (seconds < 120) {
      return "1 to 2 minutes";
    }
    if (seconds < 300) {
      return "2 to 5 minutes";
    }
    return "more than 5 minutes";
  }

  /**
   * Only a judgment of the inputs Jev would see now may open support. A new time bucket keeps it
   * valid, and so does an input that leaves the recent inputs unchanged; any other input waits for
   * its own judgment.
   *
   * @param state episode state
   * @return whether the current inputs were judged as stuck
   */
  private boolean stuck(EpisodeState state) {
    return state.judgment != null
        && recentInputs(state).equals(state.judgedInputs)
        && state.judgment.stuckProbability() >= thresholds.stuckProbability();
  }

  /**
   * Returns each registered person's latest inputs, as Jev sees them.
   *
   * @param state episode state
   * @return at most {@link #MAX_INPUTS_PER_PERSON} inputs per person, oldest first
   */
  private static Map<UUID, List<Input>> recentInputs(EpisodeState state) {
    Map<UUID, List<Input>> inputs = new LinkedHashMap<>();
    state.participants.forEach(id -> inputs.put(id, new ArrayList<>()));
    state.inputs.forEach(
        input ->
            inputs
                .computeIfAbsent(input.participantId(), ignored -> new ArrayList<>())
                .add(input.input()));
    inputs.replaceAll(
        (id, list) ->
            List.copyOf(
                list.subList(Math.max(0, list.size() - MAX_INPUTS_PER_PERSON), list.size())));
    return Collections.unmodifiableMap(inputs);
  }

  private static void record(
      EpisodeState state,
      UUID participantId,
      String kind,
      String objectId,
      String value,
      long nowSeconds) {
    startIfNeeded(state, nowSeconds);
    state.participants.add(participantId);
    boolean duplicate =
        state.inputs.reversed().stream()
            .filter(earlier -> earlier.participantId().equals(participantId))
            .findFirst()
            .filter(
                last ->
                    nowSeconds - last.seconds() <= DUPLICATE_INPUT_SECONDS
                        && last.input().kind().equals(kind)
                        && last.input().objectId().equals(objectId)
                        && last.input().value().equals(value))
            .isPresent();
    if (duplicate) {
      return;
    }
    boolean repeated =
        state.inputs.stream()
            .anyMatch(
                earlier ->
                    earlier.input().objectId().equals(objectId)
                        && earlier.input().value().equals(value));
    boolean quick =
        state.inputs.reversed().stream()
            .filter(earlier -> earlier.participantId().equals(participantId))
            .findFirst()
            .map(earlier -> nowSeconds - earlier.seconds() <= QUICK_INPUT_SECONDS)
            .orElse(false);
    state.inputs.add(
        new TimedInput(
            participantId, nowSeconds, new Input(kind, objectId, value, repeated, quick)));
  }

  private static void startIfNeeded(EpisodeState state, long nowSeconds) {
    if (!state.started) {
      state.started = true;
      state.lastProgressSeconds = nowSeconds;
    }
  }

  private void progress(EpisodeState state, long nowSeconds) {
    lastRoomProgressSeconds = nowSeconds;
    resetObservation(state, nowSeconds);
    state.hintAccepted = false;
  }

  /**
   * Starts a new observation phase: blockage must be shown again by new inputs.
   *
   * @param state episode state to reset
   * @param nowSeconds start of the new phase
   */
  private static void resetObservation(EpisodeState state, long nowSeconds) {
    startIfNeeded(state, nowSeconds);
    state.lastProgressSeconds = nowSeconds;
    state.inputs.clear();
    state.phase++;
    state.judgment = null;
    state.judgedInputs = null;
  }
}
