package feature.hud.dialogs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.scenes.scene2d.Group;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests the non-blocking dialog observation hook used by the frame recorder. */
class DialogFactoryCreationListenerTest {
  @Test
  void creationListenerSeesTransportedContextAndCanBeRemoved() {
    DialogFactory.register(TestDialogType.RECORDING, ignored -> new Group());
    AtomicInteger calls = new AtomicInteger();
    Runnable remove =
        DialogFactory.addCreationListener(
            context -> {
              assertEquals(
                  "pilot.recording_sync", context.require("recordingMarker", String.class));
              calls.incrementAndGet();
            });
    DialogContext context =
        DialogContext.builder()
            .type(TestDialogType.RECORDING)
            .center(false)
            .put("recordingMarker", "pilot.recording_sync")
            .build();

    DialogFactory.create(context);
    remove.run();
    DialogFactory.create(context);

    assertEquals(1, calls.get());
  }

  @Test
  void listenerFailureDoesNotPreventDialogCreation() {
    DialogFactory.register(TestDialogType.FAILURE, ignored -> new Group());
    Runnable remove =
        DialogFactory.addCreationListener(
            ignored -> {
              throw new IllegalStateException("simulated listener failure");
            });
    DialogContext context =
        DialogContext.builder().type(TestDialogType.FAILURE).center(false).build();

    assertDoesNotThrow(() -> DialogFactory.create(context));
    remove.run();
  }

  private enum TestDialogType implements DialogType {
    RECORDING("recording-listener-test"),
    FAILURE("recording-listener-failure-test");

    private final String type;

    TestDialogType(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }
  }
}
