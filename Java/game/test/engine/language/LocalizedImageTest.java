package engine.language;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies the real localized image assets used by Last Hour image popups. */
class LocalizedImageTest {
  private final Language originalLanguage = Localization.getInstance().currentLanguage();

  @AfterEach
  void restoreLanguage() {
    Localization.getInstance().currentLanguage(originalLanguage);
  }

  @ParameterizedTest
  @CsvSource({
    "EN, virus-phrases, virus-phrases",
    "DE, virus-phrases, virus-phrases_de",
    "EN, scientist_profile, scientist_profile",
    "DE, scientist_profile, scientist_profile_de",
    "EN, final-code, final-code",
    "DE, final-code, final-code_de"
  })
  void resolvesExistingImages(Language language, String baseName, String expectedName) {
    Localization.getInstance().currentLanguage(language);
    assertEquals(
        "images/" + expectedName + ".png",
        Localization.getInstance().asset("images/" + baseName + ".png"));
  }
}
