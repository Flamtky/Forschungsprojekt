package feature.puzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import engine.language.Language;
import engine.language.Localization;
import engine.utils.components.path.SimpleIPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks the image path shared by the puzzle UI and generated inventory pieces without OpenGL. */
class PuzzleImagePathTest {
  private final Language originalLanguage = Localization.getInstance().currentLanguage();

  @AfterEach
  void restoreLanguage() {
    Localization.getInstance().currentLanguage(originalLanguage);
  }

  @ParameterizedTest
  @CsvSource({"EN, images/final-code.png", "DE, images/final-code_de.png"})
  void resolvesDisplayImageAndPreservesBasePath(Language language, String expected) {
    Puzzle puzzle =
        new Puzzle("test", new SimpleIPath("images/final-code.png"), 4, 42L, false, null);
    Localization.getInstance().currentLanguage(language);

    assertEquals(expected, puzzle.localizedImagePath().pathString());
    assertEquals("images/final-code.png", puzzle.imagePath().pathString());
  }
}
