package feature.hud.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.Game;
import engine.game.PreRunConfiguration;
import feature.components.UIComponent;
import feature.hud.UIUtils;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testingUtils.MockNetworkHandler;

/** Tests Yes/No dialog closing without creating the visual dialog. */
class DialogFactoryYesNoTest {
  @BeforeEach
  void setUp() {
    Game.removeAllEntities();
    Game.removeAllSystems();
    MockNetworkHandler.useLocalNetworkHandler();
    PreRunConfiguration.multiplayerEnabled(false);
    PreRunConfiguration.isNetworkServer(true);
  }

  @AfterEach
  void tearDown() {
    Game.removeAllEntities();
    Game.removeAllSystems();
  }

  @Test
  void nonClosableOfferRequiresAnExplicitAnswer() {
    for (String answer : new String[] {DialogContextKeys.ON_YES, DialogContextKeys.ON_NO}) {
      AtomicInteger yes = new AtomicInteger();
      AtomicInteger no = new AtomicInteger();
      UIComponent ui =
          DialogFactory.showYesNoDialog(
              "Open a hint?", "Hint offer", yes::incrementAndGet, no::incrementAndGet, false);
      var owner = ui.dialogContext().ownerEntity();

      assertFalse(ui.canBeClosed());
      assertFalse(ui.callbacks().containsKey(DialogContextKeys.ON_CLOSE));
      UIUtils.closeDialog(ui);
      assertTrue(owner.fetch(UIComponent.class).isPresent());
      assertEquals(0, yes.get());
      assertEquals(0, no.get());

      ui.callbacks().get(answer).accept(null);

      assertTrue(owner.fetch(UIComponent.class).isEmpty());
      assertEquals(answer.equals(DialogContextKeys.ON_YES) ? 1 : 0, yes.get());
      assertEquals(answer.equals(DialogContextKeys.ON_NO) ? 1 : 0, no.get());
    }
  }
}
