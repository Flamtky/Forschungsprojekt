package engine.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Checks the public deployment default while retaining explicit local development overrides. */
class TrackingConfigTest {
  @Test
  void roomUsesPublicBackendWithoutDeploymentOverride() {
    Assumptions.assumeTrue(System.getenv("DUNGEON_TRACKING_ENDPOINT") == null);
    String previous = System.getProperty(TrackingConfig.ENDPOINT_PROPERTY);
    try {
      System.clearProperty(TrackingConfig.ENDPOINT_PROPERTY);
      assertEquals(
          URI.create("https://dungeon.flamtky.dev"),
          TrackingConfig.forRoom("the-last-hour").endpoint());
    } finally {
      restoreEndpoint(previous);
    }
  }

  @Test
  void localBackendCanStillBeExplicitlyConfigured() {
    String previous = System.getProperty(TrackingConfig.ENDPOINT_PROPERTY);
    try {
      System.setProperty(TrackingConfig.ENDPOINT_PROPERTY, "http://127.0.0.1:8088");
      assertEquals(
          URI.create("http://127.0.0.1:8088"), TrackingConfig.forRoom("the-last-hour").endpoint());
    } finally {
      restoreEndpoint(previous);
    }
  }

  private static void restoreEndpoint(String value) {
    if (value == null) System.clearProperty(TrackingConfig.ENDPOINT_PROPERTY);
    else System.setProperty(TrackingConfig.ENDPOINT_PROPERTY, value);
  }
}
