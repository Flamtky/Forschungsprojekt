package tracking.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Versioned replay questionnaire contract. This protocol carries metadata and answers, never video.
 */
public final class ReplayProtocol {
  public static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  public static final int MAX_BODY_BYTES = 65536;

  private ReplayProtocol() {}

  /**
   * Signed by the game server; no global credential is given to participants.
   *
   * @param planJson exact signed plan JSON
   * @param signature purpose-bound HMAC
   */
  public record Ticket(String planJson, String signature) {}

  /**
   * Locally persisted launch configuration.
   *
   * @param endpoint backend reachable from the client
   * @param ticket recording-scoped credential
   * @param deletionHash hash of the supervisor-only release code
   */
  public record Launch(String endpoint, Ticket ticket, String deletionHash) {}

  /**
   * One recording and its ordered selection of at most eight moments.
   *
   * @param version questionnaire schema version
   * @param sessionId tracking session UUID
   * @param participantId tracking participant UUID
   * @param studyId pseudonymous study identifier
   * @param recordingId local recording UUID
   * @param trackingSyncMs server sync time
   * @param videoSyncMs matching local video time
   * @param clips ordered selection
   */
  public record Plan(
      int version,
      String sessionId,
      String participantId,
      String studyId,
      String recordingId,
      long trackingSyncMs,
      long videoSyncMs,
      List<Clip> clips) {}

  /**
   * Times refer to the local recording. Risk/action labels are not sent to the questionnaire UI.
   *
   * @param id neutral sequential identifier
   * @param eventSequence source tracking event; for LOW baselines the risk event that opened the
   *     sampled phase
   * @param episode E1 to E6
   * @param risk sampled risk; for interventions the participant's risk at the decision, if known
   * @param action intervention action or NONE
   * @param eventMs server event time; for LOW baselines the sampled moment inside the phase
   * @param startMs clip start in video
   * @param splitMs baseline end in video
   * @param endMs continuation end in video
   */
  public record Clip(
      String id,
      long eventSequence,
      String episode,
      String risk,
      String action,
      long eventMs,
      long startMs,
      long splitMs,
      long endMs) {
    /**
     * Identifies two-stage clips.
     *
     * @return whether a continuation is required
     */
    public boolean intervention() {
      return !action.equals("NONE");
    }
  }

  /**
   * Baseline saved before any intervention continuation is released.
   *
   * @param frustrated EES rating, 1 to 5
   * @param irritated EES rating, 1 to 5
   * @param dissatisfied EES rating, 1 to 5
   * @param supportNeed support urgency, 1 to 5
   * @param desiredReaction episode-specific desired support
   */
  public record Baseline(
      int frustrated, int irritated, int dissatisfied, int supportNeed, String desiredReaction) {}

  /**
   * Non-observable support is recorded explicitly.
   *
   * @param visible whether support can be evaluated
   * @param timing EARLY, APPROPRIATE or LATE, otherwise null
   * @param intensity WEAK, APPROPRIATE or STRONG, otherwise null
   * @param fit fit rating, otherwise null
   * @param helpfulness helpfulness rating, otherwise null
   */
  public record Intervention(
      boolean visible, String timing, String intensity, Integer fit, Integer helpfulness) {}

  /**
   * Response to one clip.
   *
   * @param clipId neutral clip ID
   * @param baseline initial ratings
   * @param intervention continuation ratings, if applicable
   */
  public record Answer(String clipId, Baseline baseline, Intervention intervention) {}

  /**
   * Participant responses.
   *
   * @param answers ordered clip responses
   * @param frustrationComment optional final comment
   * @param supportComment optional final comment
   */
  public record Responses(List<Answer> answers, String frustrationComment, String supportComment) {}

  /**
   * Complete network submission.
   *
   * @param ticket server-issued scope
   * @param responses validated questionnaire
   */
  public record Submission(Ticket ticket, Responses responses) {}

  /**
   * Issues a scoped credential for one plan.
   *
   * @param plan approved selection
   * @param secret server-only API key
   * @return signed ticket
   */
  public static Ticket sign(Plan plan, String secret) {
    validatePlan(plan);
    String json = JSON.writeValueAsString(plan);
    return new Ticket(json, signature(json, secret));
  }

  /**
   * Authenticates and validates a submitted plan.
   *
   * @param ticket participant ticket
   * @param secret backend-only API key
   * @return authenticated plan
   */
  public static Plan verify(Ticket ticket, String secret) {
    require(
        ticket != null && ticket.planJson() != null && ticket.signature() != null,
        "Missing replay ticket");
    require(ticket.planJson().length() <= 32000, "Replay ticket too large");
    require(
        MessageDigest.isEqual(
            signature(ticket.planJson(), secret).getBytes(StandardCharsets.UTF_8),
            ticket.signature().getBytes(StandardCharsets.UTF_8)),
        "Invalid replay ticket");
    return readPlan(ticket);
  }

  /**
   * Reads the local plan; authentication remains the backend's responsibility.
   *
   * @param ticket received ticket
   * @return structurally valid plan
   */
  public static Plan readPlan(Ticket ticket) {
    Plan plan = JSON.readValue(ticket.planJson(), Plan.class);
    validatePlan(plan);
    return plan;
  }

  /**
   * Computes a SHA-256 digest for release codes and idempotent submissions.
   *
   * @param value exact input text
   * @return hexadecimal digest
   */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String signature(String value, String secret) {
    require(
        secret != null && secret.length() >= 32,
        "Replay requires an API key of at least 32 characters");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(("last-hour-replay-v1\n" + value).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /**
   * Checks bounded identifiers, sampling metadata and clip intervals.
   *
   * @param plan candidate plan
   */
  public static void validatePlan(Plan plan) {
    require(plan != null && plan.version() == 1, "Unsupported replay version");
    UUID.fromString(plan.sessionId());
    UUID.fromString(plan.participantId());
    UUID.fromString(plan.recordingId());
    require(
        plan.studyId() != null && !plan.studyId().isBlank() && plan.studyId().length() <= 100,
        "Invalid study ID");
    require(plan.trackingSyncMs() >= 0 && plan.videoSyncMs() >= 0, "Invalid sync marker");
    require(
        plan.clips() != null && !plan.clips().isEmpty() && plan.clips().size() <= 8,
        "Replay needs 1 to 8 clips");
    for (int i = 0; i < plan.clips().size(); i++) {
      Clip clip = plan.clips().get(i);
      require(clip != null && clip.id().equals("S" + (i + 1)), "Invalid clip order");
      require(
          Set.of("E1", "E2", "E3", "E4", "E5", "E6").contains(clip.episode()), "Invalid episode");
      require(Set.of("LOW", "MEDIUM", "HIGH", "").contains(clip.risk()), "Invalid risk");
      require(
          Set.of("NONE", "HINT_OFFER", "STORAGE_SIMPLIFY", "STORAGE_AUTO_COMPLETE")
              .contains(clip.action()),
          "Invalid action");
      require(
          clip.eventSequence() > 0
              && clip.eventMs() >= 0
              && clip.startMs() >= 0
              && clip.splitMs() > clip.startMs()
              && clip.endMs() >= clip.splitMs()
              && clip.endMs() - clip.startMs() <= 60000,
          "Invalid clip interval");
      require(!clip.intervention() || clip.endMs() > clip.splitMs(), "Missing continuation");
    }
  }

  /**
   * Partial local saves must be an ordered prefix; only the final stage may be unfinished.
   *
   * @param plan signed selection
   * @param responses candidate responses
   * @param complete whether all stages are required
   */
  public static void validateResponses(Plan plan, Responses responses, boolean complete) {
    require(responses != null && responses.answers() != null, "Missing responses");
    int count = responses.answers().size();
    require(
        count <= plan.clips().size() && (!complete || count == plan.clips().size()),
        "Incomplete questionnaire");
    for (int i = 0; i < count; i++) {
      Clip clip = plan.clips().get(i);
      Answer answer = responses.answers().get(i);
      require(answer != null && clip.id().equals(answer.clipId()), "Invalid answer order");
      Baseline baseline = answer.baseline();
      require(baseline != null, "Missing baseline");
      rating(baseline.frustrated());
      rating(baseline.irritated());
      rating(baseline.dissatisfied());
      rating(baseline.supportNeed());
      Set<String> reactions =
          clip.episode().equals("E3")
              ? Set.of("NONE", "HINT", "SIMPLIFY", "AUTO_COMPLETE")
              : Set.of("NONE", "HINT");
      require(
          baseline.desiredReaction() != null && reactions.contains(baseline.desiredReaction()),
          "Invalid desired support");
      Intervention intervention = answer.intervention();
      if (!clip.intervention()) {
        require(intervention == null, "Unexpected intervention rating");
      } else if (intervention == null) {
        require(!complete && i == count - 1, "Missing intervention rating");
      } else if (intervention.visible()) {
        require(
            intervention.timing() != null
                && Set.of("EARLY", "APPROPRIATE", "LATE").contains(intervention.timing()),
            "Invalid timing");
        require(
            intervention.intensity() != null
                && Set.of("WEAK", "APPROPRIATE", "STRONG").contains(intervention.intensity()),
            "Invalid intensity");
        rating(intervention.fit());
        rating(intervention.helpfulness());
      } else {
        require(
            intervention.timing() == null
                && intervention.intensity() == null
                && intervention.fit() == null
                && intervention.helpfulness() == null,
            "Unobservable interventions cannot be rated");
      }
    }
    require(
        responses.frustrationComment() != null
            && responses.frustrationComment().length() <= 2000
            && responses.supportComment() != null
            && responses.supportComment().length() <= 2000,
        "Comments must contain at most 2000 characters");
  }

  private static void rating(Integer value) {
    require(value != null && value >= 1 && value <= 5, "Ratings must be between 1 and 5");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
