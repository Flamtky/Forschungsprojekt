package rooms.lasthour.recording;

import engine.network.messages.c2s.RecordingFinalizationResult;
import engine.network.messages.s2c.RecordingFinalizationComplete.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Mutable server-side state for one coordinated client-recording shutdown. */
final class LastHourFinalizationState {
  enum Acceptance {
    ACCEPTED,
    DUPLICATE,
    UNKNOWN_CLIENT,
    WRONG_COMPLETION
  }

  record ExpectedClient(short clientId, String participantId, String visibleRecordingId) {
    ExpectedClient {
      participantId = Objects.requireNonNullElse(participantId, "").trim();
      visibleRecordingId = Objects.requireNonNullElse(visibleRecordingId, "").trim();
    }
  }

  private final UUID completionId;
  private final boolean gameplayCompleted;
  private final String reason;
  private final int requiredClientCount;
  private final int timeoutSeconds;
  private final Instant startedAt;
  private final long deadlineNanos;
  private final Map<Short, ExpectedClient> expectedClients = new LinkedHashMap<>();
  private final Map<Short, RecordingFinalizationResult> results = new LinkedHashMap<>();
  private final Set<Short> persistedFinalizationEvents = new HashSet<>();
  private boolean timedOut;
  private Instant finalizedAt;

  LastHourFinalizationState(
      UUID completionId,
      boolean gameplayCompleted,
      String reason,
      int requiredClientCount,
      int timeoutSeconds,
      Instant startedAt,
      long startedNanos,
      Collection<ExpectedClient> clients) {
    this.completionId = Objects.requireNonNull(completionId, "completionId");
    this.gameplayCompleted = gameplayCompleted;
    this.reason = Objects.requireNonNullElse(reason, "");
    this.requiredClientCount = requiredClientCount;
    this.timeoutSeconds = timeoutSeconds;
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.deadlineNanos =
        startedNanos + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
    if (requiredClientCount < 1 || timeoutSeconds < 1) {
      throw new IllegalArgumentException("Client count and timeout must be positive");
    }
    clients.stream()
        .sorted(java.util.Comparator.comparingInt(ExpectedClient::clientId))
        .forEach(client -> expectedClients.put(client.clientId(), client));
  }

  Acceptance accept(short clientId, RecordingFinalizationResult result) {
    Objects.requireNonNull(result, "result");
    if (!completionId.equals(result.completionId())) {
      return Acceptance.WRONG_COMPLETION;
    }
    if (!expectedClients.containsKey(clientId)) {
      return Acceptance.UNKNOWN_CLIENT;
    }
    if (results.containsKey(clientId)) {
      return Acceptance.DUPLICATE;
    }
    results.put(clientId, result);
    return Acceptance.ACCEPTED;
  }

  UUID completionId() {
    return completionId;
  }

  boolean gameplayCompleted() {
    return gameplayCompleted;
  }

  String reason() {
    return reason;
  }

  int requiredClientCount() {
    return requiredClientCount;
  }

  int timeoutSeconds() {
    return timeoutSeconds;
  }

  Instant startedAt() {
    return startedAt;
  }

  long deadlineNanos() {
    return deadlineNanos;
  }

  Map<Short, ExpectedClient> expectedClients() {
    return Map.copyOf(expectedClients);
  }

  Map<Short, RecordingFinalizationResult> results() {
    return Map.copyOf(results);
  }

  List<Short> missingClientIds() {
    return expectedClients.keySet().stream().filter(id -> !results.containsKey(id)).toList();
  }

  boolean allResultsReceived() {
    return results.size() == expectedClients.size();
  }

  void markFinalizationEventPersisted(short clientId) {
    if (results.containsKey(clientId)) {
      persistedFinalizationEvents.add(clientId);
    }
  }

  boolean finalizationEventPersisted(short clientId) {
    return persistedFinalizationEvents.contains(clientId);
  }

  boolean allFinalizationEventsPersisted() {
    return persistedFinalizationEvents.size() == expectedClients.size()
        && persistedFinalizationEvents.containsAll(results.keySet());
  }

  List<Short> unpersistedResultClientIds() {
    return results.keySet().stream()
        .filter(id -> !persistedFinalizationEvents.contains(id))
        .toList();
  }

  boolean deadlineReached(long nowNanos) {
    return nowNanos >= deadlineNanos;
  }

  void finish(boolean timeout, Instant time) {
    timedOut = timeout;
    finalizedAt = Objects.requireNonNull(time, "time");
  }

  boolean timedOut() {
    return timedOut;
  }

  Instant finalizedAt() {
    return finalizedAt;
  }

  RunStatus runStatus() {
    if (!gameplayCompleted) {
      return RunStatus.ABORTED;
    }
    return recordingsValid() ? RunStatus.COMPLETE : RunStatus.INVALID;
  }

  /**
   * Checks recordings and identities independently of how the game ended. Operator-stopped runs
   * stay ABORTED but still receive the replay questionnaire when this holds.
   *
   * @return whether at least one participant delivered a usable, uniquely mapped recording
   */
  boolean recordingsValid() {
    return expectedClients.keySet().stream().anyMatch(this::recordingUsable);
  }

  /**
   * Checks this participant independently of missing or unusable partner recordings.
   *
   * @param clientId client whose final recording is being checked
   * @return whether its persisted recording matches the visible sync and maps uniquely
   */
  boolean recordingUsable(short clientId) {
    if (!recordingCandidate(clientId)) {
      return false;
    }
    ExpectedClient client = expectedClients.get(clientId);
    RecordingFinalizationResult result = results.get(clientId);
    List<Short> candidates =
        expectedClients.keySet().stream().filter(this::recordingCandidate).toList();
    return occurrences(
                candidates.stream().map(id -> expectedClients.get(id).participantId()),
                client.participantId())
            == 1
        && occurrences(candidates.stream().map(id -> results.get(id).studyId()), result.studyId())
            == 1
        && occurrences(
                candidates.stream().map(id -> results.get(id).recordingId()), result.recordingId())
            == 1;
  }

  private boolean recordingCandidate(short clientId) {
    ExpectedClient client = expectedClients.get(clientId);
    RecordingFinalizationResult result = results.get(clientId);
    return client != null
        && result != null
        && result.usableForReplay()
        && !client.participantId().isBlank()
        && !client.visibleRecordingId().isBlank()
        && client.visibleRecordingId().equalsIgnoreCase(result.recordingId().trim())
        && finalizationEventPersisted(clientId);
  }

  private static long occurrences(java.util.stream.Stream<String> identifiers, String candidate) {
    return identifiers
        .map(identifier -> identifier.trim().toLowerCase(Locale.ROOT))
        .filter(identifier -> identifier.equals(candidate.trim().toLowerCase(Locale.ROOT)))
        .count();
  }
}
