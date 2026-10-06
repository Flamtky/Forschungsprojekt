package rooms.lasthour.util.translation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.language.Language;
import engine.language.Localization;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests replacement of overlapping vent translation keys. */
class LastHourTranslatorTest {

  @ParameterizedTest
  @EnumSource(Language.class)
  void translatesDecoyAndRealVentWithoutMatchingInsideLongerKeys(Language language) {
    Localization.getInstance()
        .registerTranslationFile(language, "language/theLastHour/" + language + ".json");
    LastHourTranslator translator = new LastHourTranslator();
    translator.translation.language(language);

    String decoy = translator.translate(TranslationKey.DecoyVentDialog1);

    assertTrue(decoy.contains("sv00057---"));
    assertFalse(decoy.contains("Decoy"));
    assertFalse(decoy.contains("49221"));
    assertTrue(translator.translate(TranslationKey.VentDialog).contains("49221"));
  }
}
