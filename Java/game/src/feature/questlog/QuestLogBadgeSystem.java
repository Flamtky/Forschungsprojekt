package feature.questlog;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.Align;
import engine.Game;
import engine.System;
import engine.components.PlayerComponent;
import engine.utils.FontSpec;
import engine.utils.Scene2dElementFactory;
import engine.utils.components.draw.TextureMap;
import engine.utils.components.path.SimpleIPath;
import feature.input.configuration.KeyboardConfig;
import java.util.Optional;

/** Displays the quest log shortcut and unread entry count in the client HUD. */
public final class QuestLogBadgeSystem extends System {

  private static final float CORNER_MARGIN = 24f;
  private static final float ICON_SIZE = 44f;
  private static final int COUNTER_SIZE = 22;
  private static int seenEntryCount;

  private Group badge;
  private Group counter;
  private Label counterLabel;
  private Label keyLabel;

  /** Creates a quest log badge system that updates every client frame. */
  public QuestLogBadgeSystem() {
    super(AuthoritativeSide.CLIENT);
  }

  /** Updates the badge position, shortcut, and unread count when a stage is available. */
  @Override
  public void execute() {
    if (Game.isHeadless()) {
      return;
    }
    Game.stage()
        .ifPresent(
            stage -> {
              if (badge == null) {
                createBadge();
              }
              if (badge.getStage() == null) {
                stage.addActor(badge);
              }
              badge.setPosition(stage.getWidth() - badge.getWidth() - CORNER_MARGIN, CORNER_MARGIN);
              keyLabel.setText(Input.Keys.toString(KeyboardConfig.QUESTLOG_OPEN.value()));

              Optional<Integer> visibleCount = localVisibleEntryCount();
              badge.setVisible(visibleCount.isPresent());
              if (visibleCount.isEmpty()) {
                return;
              }
              int unread = Math.max(0, visibleCount.get() - seenEntryCount);
              counter.setVisible(unread > 0);
              counterLabel.setText(unread >= 10 ? "9+" : Integer.toString(unread));
            });
  }

  /** The badge stays current while dialogs pause the game. */
  @Override
  public void stop() {
    run = true;
  }

  /** Marks all currently visible entries as read if a local player and quest log exist. */
  public static void markAllRead() {
    localVisibleEntryCount().ifPresent(count -> seenEntryCount = count);
  }

  private static Optional<Integer> localVisibleEntryCount() {
    return Game.player()
        .flatMap(player -> player.fetch(PlayerComponent.class))
        .flatMap(
            player ->
                QuestLogUtil.getQuestLogComponent()
                    .map(questLog -> QuestLogUI.visibleEntryCount(questLog, player.playerName())));
  }

  private void createBadge() {
    badge = new Group();
    badge.setTouchable(Touchable.disabled);
    badge.setSize(ICON_SIZE + COUNTER_SIZE / 2f, 20f + ICON_SIZE + COUNTER_SIZE / 2f);

    Texture book =
        TextureMap.instance().textureAt(new SimpleIPath("items/rpg/item_book_brown.png"));
    book.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
    Image icon = new Image(book);
    icon.setBounds(0f, 20f, ICON_SIZE, ICON_SIZE);
    badge.addActor(icon);

    counter = new Group();
    counter.setBounds(
        ICON_SIZE - COUNTER_SIZE / 2f,
        20f + ICON_SIZE - COUNTER_SIZE / 2f,
        COUNTER_SIZE,
        COUNTER_SIZE);
    Pixmap circle = new Pixmap(COUNTER_SIZE, COUNTER_SIZE, Pixmap.Format.RGBA8888);
    circle.setColor(Color.RED);
    circle.fillCircle(COUNTER_SIZE / 2, COUNTER_SIZE / 2, COUNTER_SIZE / 2 - 1);
    Texture circleTexture = new Texture(circle);
    circle.dispose();
    counter.addActor(new Image(circleTexture));
    counterLabel =
        Scene2dElementFactory.createLabel(
            "", FontSpec.of("fonts/Roboto-Bold.ttf", 14, Color.WHITE));
    counterLabel.setAlignment(Align.center);
    counterLabel.setBounds(0f, 0f, COUNTER_SIZE, COUNTER_SIZE);
    counter.addActor(counterLabel);
    badge.addActor(counter);

    keyLabel =
        Scene2dElementFactory.createLabel(
            "", FontSpec.of("fonts/Roboto-Regular.ttf", 14, Color.GRAY));
    keyLabel.setAlignment(Align.center);
    keyLabel.setBounds(0f, 0f, ICON_SIZE, 18f);
    badge.addActor(keyLabel);
  }
}
