package rooms.lasthour.level;

import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import engine.Game;
import engine.language.Translation;
import engine.utils.BaseContainerUI;
import feature.components.UIComponent;
import feature.hud.UIUtils;
import feature.hud.dialogs.DialogContext;
import feature.hud.dialogs.DialogDesign;
import feature.hud.dialogs.DialogFactory;
import feature.hud.dialogs.DialogType;
import feature.hud.dialogs.HeadlessDialogGroup;
import feature.hud.elements.RichLabel;

/** Waiting message closed by the server when the team first becomes complete. */
public final class TeamWaitingDialog {
  private static final DialogType TYPE = () -> "lasthour-team-waiting";

  private TeamWaitingDialog() {}

  /** Registers the waiting UI on both server and clients. */
  public static void register() {
    DialogFactory.register(TYPE, TeamWaitingDialog::build);
  }

  static UIComponent show(int playerId) {
    return DialogFactory.show(DialogContext.builder().type(TYPE).build(), true, false, playerId);
  }

  private static Group build(DialogContext context) {
    String text = new Translation("translation").text("TeamWaiting");
    if (Game.isHeadless()) return new HeadlessDialogGroup("", text);
    Dialog dialog = new Dialog("", UIUtils.defaultSkin());
    DialogDesign.setDialogDefaults(dialog, "");
    RichLabel label = new RichLabel(text, DialogDesign.DIALOG_FONT_SPEC_NORMAL);
    label.setWrap(true);
    dialog.getContentTable().add(label).width(500).pad(20);
    dialog.pack();
    return new BaseContainerUI(dialog);
  }
}
