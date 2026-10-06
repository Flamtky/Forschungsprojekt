package rooms.lasthour.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests the neutralization phrases shared by server validation and the virus tab. */
class LoreTest {

  @ParameterizedTest
  @CsvSource({
    "Trojan, verify before trust, true",
    "Ransomware, backup your data, true",
    "Adware, read before click, true",
    "Trojan, 'ERST ÜBERPRÜFEN, DANN VERTRAUEN', true",
    "Trojan, ERST ÜBERPRÜFEN DANN VERTRAUEN, true",
    "Trojan, erstüberprüfendannvertrauen, true",
    "Trojan, 'ErSt ÜbErPrÜfEn, DaNn VeRtRaUeN!', true",
    "Ransomware, SICHERN SIE IHRE DATEN, true",
    "Ransomware, sichernSieIhreDaten, true",
    "Adware, VOR DEM KLICKEN LESEN, true",
    "Adware, vorDemKlickenLesen, true",
    "Trojan, 'VeRiFy-123 BeFoRe, TrUsT!', true",
    "Trojan, backup your data, false",
    "Adware, SICHERN SIE IHRE DATEN, false",
    "Trojan, ERST BERPRFEN DANN VERTRAUEN, false",
    "Unknown Device, verify before trust, false",
    ", verify before trust, false",
    "Trojan, , false",
    "Trojan, '', false"
  })
  void acceptsOnlyPhrasesForTheVirusType(String virusType, String input, boolean expected) {
    assertEquals(expected, Lore.isVirusCode(virusType, input));
  }
}
