package rooms.lasthour.recording;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tracking.core.ReplayProtocol;
import tracking.core.ReplayProtocol.Clip;
import tracking.core.ReplayProtocol.Plan;
import tracking.core.TrackingEvent;
import tracking.core.TrackingEventType;

/** Reproducible pilot sampling, using the participant's persisted visible sync marker. */
final class ReplayPlanner {
  private static final long RISK_BEFORE_MS = 25000;
  private static final long RISK_AFTER_MS = 5000;
  private static final long INTERVENTION_BEFORE_MS = 30000;
  private static final long INTERVENTION_AFTER_MS = 20000;

  /**
   * A selected moment.
   *
   * @param source decision or risk event behind the clip
   * @param risk participant's risk label for the clip
   * @param eventMs event time; for LOW baselines a moment inside their phase
   */
  private record Sample(TrackingEvent source, String risk, long eventMs) {
    boolean intervention() {
      return object(source).equals("adaptation.decision");
    }
  }

  /**
   * A stretch in which every active episode of one participant stayed LOW.
   *
   * @param source risk event of the most recently changed active episode at the phase start
   * @param startMs phase start in tracking time
   * @param endMs phase end in tracking time
   */
  private record LowPhase(TrackingEvent source, long startMs, long endMs) {
    long length() {
      return endMs - startMs;
    }

    long middle() {
      return startMs + length() / 2;
    }
  }

  private ReplayPlanner() {}

  static Plan create(
      List<TrackingEvent> events,
      UUID participantId,
      String studyId,
      String recordingId,
      long durationMs) {
    TrackingEvent visible =
        events.stream()
            .filter(event -> own(event, participantId))
            .filter(event -> object(event).equals("pilot.recording_sync_visible"))
            .max(Comparator.comparingLong(TrackingEvent::sessionSequence))
            .orElseThrow();
    if (!text(visible, "recordingId").equalsIgnoreCase(recordingId)) {
      throw new IllegalArgumentException("Recording is not the participant's latest visible sync");
    }
    // A restart has a new video clock. Pair its acknowledgement with that connection's intro.
    TrackingEvent sync =
        events.stream()
            .filter(event -> own(event, participantId))
            .filter(event -> object(event).equals("pilot.recording_sync"))
            .filter(event -> event.sessionSequence() < visible.sessionSequence())
            .max(Comparator.comparingLong(TrackingEvent::sessionSequence))
            .orElseThrow();
    long videoSync = visible.payload().get("videoSyncMs").longValue();
    if (videoSync < 0 || videoSync >= durationMs) {
      throw new IllegalArgumentException("Visible sync is outside the finalized recording");
    }
    long offset = videoSync - sync.elapsedMonotonicMs();
    List<TrackingEvent> allDecisions =
        events.stream().filter(event -> object(event).equals("adaptation.decision")).toList();
    List<TrackingEvent> decisions =
        allDecisions.stream()
            .filter(
                event ->
                    events.stream()
                        .anyMatch(
                            applied ->
                                object(applied).equals("adaptation.intervention")
                                    && text(applied, "episode").equals(text(event, "episode"))
                                    && text(applied, "action").equals(text(event, "action"))
                                    && applied.elapsedMonotonicMs() >= event.elapsedMonotonicMs()
                                    && applied.elapsedMonotonicMs()
                                        <= event.elapsedMonotonicMs() + 5000))
            .filter(
                event ->
                    event.elapsedMonotonicMs() + offset > 0
                        && event.elapsedMonotonicMs() + offset + 1000 < durationMs)
            .limit(2)
            .toList();
    List<Sample> selected = new ArrayList<>();
    decisions.forEach(
        decision ->
            selected.add(
                new Sample(
                    decision,
                    riskAt(events, participantId, decision),
                    decision.elapsedMonotonicMs())));
    for (String risk : List.of("MEDIUM", "HIGH")) {
      events.stream()
          .filter(event -> own(event, participantId))
          .filter(
              event -> object(event).equals("adaptation.risk") && text(event, "risk").equals(risk))
          .filter(event -> !text(event, "reason").equals("episode-completed"))
          .filter(
              event ->
                  event.elapsedMonotonicMs() + offset >= 0
                      && event.elapsedMonotonicMs() + offset < durationMs)
          // Do not reveal a sampled intervention in an earlier baseline clip.
          .filter(
              event ->
                  decisions.stream()
                      .noneMatch(
                          decision ->
                              event.elapsedMonotonicMs() + RISK_AFTER_MS
                                      >= decision.elapsedMonotonicMs() - INTERVENTION_BEFORE_MS
                                  && event.elapsedMonotonicMs() - RISK_BEFORE_MS
                                      <= decision.elapsedMonotonicMs() + INTERVENTION_AFTER_MS))
          .limit(2)
          .forEach(event -> selected.add(new Sample(event, risk, event.elapsedMonotonicMs())));
    }
    selected.addAll(
        lowBaselines(events, participantId, allDecisions, -offset, durationMs - offset - 1000));
    selected.sort(
        Comparator.comparingLong(Sample::eventMs)
            .thenComparingLong(sample -> sample.source().sessionSequence()));
    List<Clip> clips = new ArrayList<>();
    for (Sample sample : selected) {
      TrackingEvent event = sample.source();
      boolean intervention = sample.intervention();
      long moment = sample.eventMs() + offset;
      long start = Math.max(0, moment - (intervention ? INTERVENTION_BEFORE_MS : RISK_BEFORE_MS));
      long split = Math.min(durationMs, intervention ? moment : moment + RISK_AFTER_MS);
      long end = intervention ? Math.min(durationMs, moment + INTERVENTION_AFTER_MS) : split;
      if (intervention) {
        end =
            events.stream()
                .filter(progress -> progress.elapsedMonotonicMs() > event.elapsedMonotonicMs())
                .filter(
                    progress ->
                        object(progress).equals("adaptation.decision")
                            || (progress.eventType() == TrackingEventType.PUZZLE_SOLVED
                                && progress.puzzleId().equals(event.puzzleId())))
                .mapToLong(progress -> progress.elapsedMonotonicMs() + offset)
                .filter(time -> time > split)
                .min()
                .stream()
                .map(time -> Math.min(time, Math.min(durationMs, moment + INTERVENTION_AFTER_MS)))
                .findFirst()
                .orElse(end);
      }
      if (split <= start || end < split || (intervention && end <= split)) continue;
      clips.add(
          new Clip(
              "S" + (clips.size() + 1),
              event.sessionSequence(),
              text(event, "episode"),
              sample.risk(),
              intervention ? text(event, "action") : "NONE",
              sample.eventMs(),
              start,
              split,
              end));
    }
    Plan plan =
        new Plan(
            1,
            sync.sessionId().toString(),
            participantId.toString(),
            studyId,
            recordingId,
            sync.elapsedMonotonicMs(),
            videoSync,
            List.copyOf(clips));
    ReplayProtocol.validatePlan(plan);
    return plan;
  }

  /**
   * Looks up the participant's own risk in the decision's episode just before the decision.
   *
   * @param events session events
   * @param participantId rated participant
   * @param decision sampled decision
   * @return LOW, MEDIUM or HIGH, or empty if the participant had no judged risk in that episode
   */
  private static String riskAt(
      List<TrackingEvent> events, UUID participantId, TrackingEvent decision) {
    return events.stream()
        .filter(event -> event.sessionSequence() < decision.sessionSequence())
        .filter(event -> own(event, participantId))
        .filter(event -> object(event).equals("adaptation.risk"))
        .filter(event -> text(event, "episode").equals(text(decision, "episode")))
        .max(Comparator.comparingLong(TrackingEvent::sessionSequence))
        .map(event -> text(event, "risk"))
        .filter(risk -> !risk.equals("NONE"))
        .orElse("");
  }

  /**
   * Picks up to two LOW baselines. A change to LOW only marks an episode start or the seconds after
   * progress, so each clip is centred in a phase in which all of the participant's active episodes
   * stayed LOW. A phase ends one risk pre-roll before a later rise, keeping it apart from that
   * MEDIUM or HIGH clip, and skips every decision window. The longest phase comes first, then the
   * longest one from the other half of the recording, if there is one.
   *
   * @param events session events
   * @param participantId rated participant
   * @param decisions all system decisions of the session
   * @param firstMs earliest tracking time covered by the recording
   * @param lastMs latest usable tracking time covered by the recording
   * @return zero to two LOW samples
   */
  private static List<Sample> lowBaselines(
      List<TrackingEvent> events,
      UUID participantId,
      List<TrackingEvent> decisions,
      long firstMs,
      long lastMs) {
    List<LowPhase> phases = new ArrayList<>();
    Map<String, TrackingEvent> active = new LinkedHashMap<>();
    LowPhase open = null;
    for (TrackingEvent event : events) {
      if (!own(event, participantId) || !object(event).equals("adaptation.risk")) {
        continue;
      }
      String episode = text(event, "episode");
      active.remove(episode);
      // NONE means no Jev judgment for this person in that episode. It is no LOW baseline, and it
      // does not block the person's judged episodes, because every player is registered in every
      // active episode, including ones the person never touches.
      if (!text(event, "reason").equals("episode-completed")
          && !text(event, "risk").equals("NONE")) {
        active.put(episode, event);
      }
      boolean low =
          !active.isEmpty()
              && active.values().stream().allMatch(risk -> text(risk, "risk").equals("LOW"));
      long now = event.elapsedMonotonicMs();
      if (open != null) {
        // Only a MEDIUM or HIGH risk gets its own clip here, which the baseline must not overlap.
        String risk = text(event, "risk");
        long end = risk.equals("MEDIUM") || risk.equals("HIGH") ? now - RISK_BEFORE_MS : now;
        phases.add(new LowPhase(open.source(), open.startMs(), end));
      }
      // The most recently changed active episode labels the phase.
      open =
          low
              ? new LowPhase(
                  active.values().stream().reduce((a, b) -> b).orElseThrow(), now, lastMs)
              : null;
    }
    if (open != null) {
      phases.add(open);
    }

    List<LowPhase> candidates = new ArrayList<>();
    for (LowPhase phase : phases) {
      long start = Math.max(phase.startMs(), firstMs);
      long end = Math.min(phase.endMs(), lastMs);
      for (TrackingEvent decision : decisions) {
        long blockedStart = decision.elapsedMonotonicMs() - INTERVENTION_BEFORE_MS;
        long blockedEnd = decision.elapsedMonotonicMs() + INTERVENTION_AFTER_MS;
        if (blockedStart < end && blockedEnd > start) {
          candidates.add(new LowPhase(phase.source(), start, blockedStart));
          start = blockedEnd;
        }
      }
      candidates.add(new LowPhase(phase.source(), start, end));
    }
    candidates.removeIf(phase -> phase.length() < RISK_BEFORE_MS + RISK_AFTER_MS);
    if (candidates.isEmpty()) {
      return List.of();
    }
    candidates.sort(
        Comparator.comparingLong(LowPhase::length).reversed().thenComparingLong(LowPhase::startMs));
    long half = firstMs + (lastMs - firstMs) / 2;
    LowPhase longest = candidates.getFirst();
    List<LowPhase> chosen = new ArrayList<>(List.of(longest));
    candidates.stream()
        .skip(1)
        .filter(phase -> (phase.middle() < half) != (longest.middle() < half))
        .findFirst()
        .or(() -> candidates.stream().skip(1).findFirst())
        .ifPresent(chosen::add);
    // Centre the window [moment - 25 s, moment + 5 s] on the middle of its phase.
    return chosen.stream()
        .map(
            phase ->
                new Sample(
                    phase.source(), "LOW", phase.middle() + (RISK_BEFORE_MS - RISK_AFTER_MS) / 2))
        .toList();
  }

  private static boolean own(TrackingEvent event, UUID participantId) {
    return event.participantId().filter(participantId::equals).isPresent();
  }

  private static String object(TrackingEvent event) {
    return event.objectId().orElse("");
  }

  private static String text(TrackingEvent event, String field) {
    var node = event.payload().get(field);
    return node == null ? "" : node.stringValue();
  }
}
