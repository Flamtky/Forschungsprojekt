package rooms.lasthour.adaptation;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import rooms.lasthour.util.LastHourPuzzle;

/** The six research episodes and their existing Last Hour puzzles. */
public enum LastHourEpisode {
  E1(LastHourPuzzle.POWER, Set.of(LastHourPuzzle.POWER)),
  E2(LastHourPuzzle.LOGIN, Set.of(LastHourPuzzle.LOGIN)),
  E3(
      LastHourPuzzle.STORAGE_ACCESS,
      Set.of(LastHourPuzzle.STORAGE_RECOVERY, LastHourPuzzle.STORAGE_ACCESS)),
  E4(LastHourPuzzle.BLUE_USB, Set.of(LastHourPuzzle.BLUE_USB)),
  E5(LastHourPuzzle.VENTILATION, Set.of(LastHourPuzzle.VENTILATION)),
  E6(LastHourPuzzle.EXIT, Set.of(LastHourPuzzle.EXIT_CODE_ASSEMBLY, LastHourPuzzle.EXIT));

  private final LastHourPuzzle trackingPuzzle;
  private final Set<LastHourPuzzle> puzzles;

  LastHourEpisode(LastHourPuzzle trackingPuzzle, Set<LastHourPuzzle> puzzles) {
    this.trackingPuzzle = trackingPuzzle;
    this.puzzles = EnumSet.copyOf(puzzles);
  }

  /**
   * Returns the canonical puzzle ID used for episode-level room events.
   *
   * @return canonical puzzle
   */
  public LastHourPuzzle trackingPuzzle() {
    return trackingPuzzle;
  }

  /**
   * Maps an existing puzzle to its research episode.
   *
   * @param puzzle existing room puzzle
   * @return research episode, or empty for puzzles outside the policy
   */
  public static Optional<LastHourEpisode> forPuzzle(LastHourPuzzle puzzle) {
    for (LastHourEpisode episode : values()) {
      if (episode.puzzles.contains(puzzle)) {
        return Optional.of(episode);
      }
    }
    return Optional.empty();
  }

  boolean terminalPuzzle(LastHourPuzzle puzzle) {
    return switch (this) {
      case E1 -> puzzle == LastHourPuzzle.POWER;
      case E2 -> puzzle == LastHourPuzzle.LOGIN;
      case E3 -> puzzle == LastHourPuzzle.STORAGE_ACCESS;
      case E4 -> puzzle == LastHourPuzzle.BLUE_USB;
      case E5 -> puzzle == LastHourPuzzle.VENTILATION;
      case E6 -> puzzle == LastHourPuzzle.EXIT;
    };
  }
}
