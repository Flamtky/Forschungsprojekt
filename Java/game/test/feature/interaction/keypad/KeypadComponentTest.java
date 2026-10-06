package feature.interaction.keypad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import engine.Entity;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class KeypadComponentTest {

  @Test
  void submitWrongCodeNotifiesBeforeClearingDigits() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});
    Entity caller = new Entity();
    AtomicReference<Entity> receivedCaller = new AtomicReference<>();
    AtomicReference<List<Integer>> receivedDigits = new AtomicReference<>();
    component.onWrongCode(
        entity -> {
          receivedCaller.set(entity);
          receivedDigits.set(List.copyOf(component.enteredDigits()));
        });
    component.addDigit(1);
    component.addDigit(2);
    component.addDigit(4);

    assertEquals(KeypadUI.SubmitResult.WRONG, KeypadUI.submit(component, caller));
    assertSame(caller, receivedCaller.get());
    assertEquals(List.of(1, 2, 4), receivedDigits.get());
    assertEquals(List.of(), component.enteredDigits());
    assertEquals(1, component.wrongCodeAttempts());

    receivedCaller.set(null);
    assertEquals(KeypadUI.SubmitResult.IGNORED, KeypadUI.submit(component, caller));
    assertSame(null, receivedCaller.get());
    assertEquals(1, component.wrongCodeAttempts());
  }

  @Test
  void submitEmptyCodeDoesNotInvokeCallbacks() {
    AtomicInteger calls = new AtomicInteger();
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), calls::incrementAndGet);
    component.onWrongCode(() -> calls.incrementAndGet());
    component.onCorrectCode(() -> calls.incrementAndGet());

    assertEquals(KeypadUI.SubmitResult.IGNORED, KeypadUI.submit(component, new Entity()));
    assertEquals(0, calls.get());
    assertEquals(0, component.wrongCodeAttempts());
  }

  @Test
  void submitIncompleteCodeNotifiesAndClearsWithoutCountingCompleteAttempt() {
    KeypadComponent component = new KeypadComponent(List.of(3, 7, 5, 8), () -> {});
    component.digitHintPattern("3*5*");
    AtomicReference<List<Integer>> receivedDigits = new AtomicReference<>();
    component.onWrongCode(() -> receivedDigits.set(List.copyOf(component.enteredDigits())));
    component.addDigit(3);

    assertEquals(KeypadUI.SubmitResult.WRONG, KeypadUI.submit(component, new Entity()));
    assertEquals(List.of(3), receivedDigits.get());
    assertEquals(List.of(), component.enteredDigits());
    assertEquals("3*5*", component.enteredString());
    assertEquals(0, component.wrongCodeAttempts());
  }

  @Test
  void submitCorrectCodeLocksFurtherInputAndIgnoresFurtherSubmissions() {
    AtomicInteger actions = new AtomicInteger();
    AtomicInteger correctCalls = new AtomicInteger();
    AtomicInteger wrongCalls = new AtomicInteger();
    KeypadComponent component = new KeypadComponent(List.of(3, 7, 5, 8), actions::incrementAndGet);
    component.digitHintPattern("3*5*");
    component.onCorrectCode(() -> correctCalls.incrementAndGet());
    component.onWrongCode(() -> wrongCalls.incrementAndGet());
    component.correctDigits().forEach(component::addDigit);
    Entity caller = new Entity();

    assertEquals(KeypadUI.SubmitResult.UNLOCKED, KeypadUI.submit(component, caller));
    assertTrue(component.isUnlocked());
    component.backspace();
    component.addDigit(9);
    assertEquals(List.of(3, 7, 5, 8), component.enteredDigits());
    assertEquals(KeypadUI.SubmitResult.IGNORED, KeypadUI.submit(component, caller));
    assertEquals(1, actions.get());
    assertEquals(1, correctCalls.get());
    assertEquals(0, wrongCalls.get());
  }

  @Test
  void clearEnteredDigitsDoesNothingWhenUnlocked() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});
    component.correctDigits().forEach(component::addDigit);
    component.checkUnlock(new Entity());

    component.clearEnteredDigits();

    assertEquals(List.of(1, 2, 3), component.enteredDigits());
    assertTrue(component.isUnlocked());
  }

  @Test
  void checkUnlockRejectsMissingCaller() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});

    assertThrows(NullPointerException.class, () -> component.checkUnlock(null));
  }

  @Test
  void onCorrectCodeRejectsNullRunnable() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});

    assertThrows(NullPointerException.class, () -> component.onCorrectCode((Runnable) null));
  }

  @Test
  void onCorrectCodeRejectsNullConsumer() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});

    assertThrows(
        NullPointerException.class, () -> component.onCorrectCode((Consumer<Entity>) null));
  }

  @Test
  void onWrongCodeRejectsNullRunnable() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});

    assertThrows(NullPointerException.class, () -> component.onWrongCode((Runnable) null));
  }

  @Test
  void onWrongCodeRejectsNullConsumer() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});

    assertThrows(NullPointerException.class, () -> component.onWrongCode((Consumer<Entity>) null));
  }

  @Test
  void correctCodeCallbackReceivesCaller() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});
    Entity caller = new Entity();
    AtomicReference<Entity> receivedCaller = new AtomicReference<>();
    component.onCorrectCode(receivedCaller::set);

    component.addDigit(1);
    component.addDigit(2);
    component.addDigit(3);
    component.checkUnlock(caller);

    assertSame(caller, receivedCaller.get());
  }

  @Test
  void wrongCodeCallbackReceivesCaller() {
    KeypadComponent component = new KeypadComponent(List.of(1, 2, 3), () -> {});
    Entity caller = new Entity();
    AtomicReference<Entity> receivedCaller = new AtomicReference<>();
    component.onWrongCode(receivedCaller::set);

    component.addDigit(1);
    component.checkUnlock(caller);

    assertSame(caller, receivedCaller.get());
    assertEquals(0, component.wrongCodeAttempts());

    receivedCaller.set(null);
    component.addDigit(2);
    component.addDigit(4);
    component.checkUnlock(caller);

    assertSame(caller, receivedCaller.get());
    assertEquals(1, component.wrongCodeAttempts());
  }

  @Test
  void digitHintPatternChangesOnlyUnenteredDisplayPositions() {
    KeypadComponent component = new KeypadComponent(List.of(3, 7, 5, 8), () -> {});
    component.digitHintPattern("3*5*");

    assertEquals("3*5*", component.enteredString());

    component.addDigit(3);
    component.addDigit(7);

    assertEquals("375*", component.enteredString());
    assertEquals(List.of(3, 7), component.enteredDigits());
    assertEquals("3758", component.correctString());
  }

  @Test
  void digitHintPatternRejectsWrongOrMismatchedDigits() {
    KeypadComponent component = new KeypadComponent(List.of(3, 7, 5, 8), () -> {});

    assertThrows(IllegalArgumentException.class, () -> component.digitHintPattern("3**"));
    assertThrows(IllegalArgumentException.class, () -> component.digitHintPattern("4*5*"));
  }
}
