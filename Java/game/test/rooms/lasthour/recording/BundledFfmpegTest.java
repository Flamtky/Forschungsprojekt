package rooms.lasthour.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import rooms.lasthour.recording.BundledFfmpeg.Platform;

/** Tests deterministic operating-system and architecture selection for bundled FFmpeg. */
class BundledFfmpegTest {
  @Test
  void detectsSupportedWindowsLinuxAndMacPlatforms() {
    assertEquals(Platform.WINDOWS_X86_64, Platform.detect("Windows 11", "amd64"));
    assertEquals(Platform.LINUX_X86_64, Platform.detect("Linux", "x86_64"));
    assertEquals(Platform.LINUX_AARCH64, Platform.detect("Linux", "aarch64"));
    assertEquals(Platform.MACOS_X86_64, Platform.detect("Mac OS X", "x86_64"));
    assertEquals(Platform.MACOS_AARCH64, Platform.detect("Darwin", "arm64"));
  }

  @Test
  void rejectsUnsupportedOperatingSystemsAndArchitectures() {
    assertThrows(IllegalStateException.class, () -> Platform.detect("Windows 11", "aarch64"));
    assertThrows(IllegalStateException.class, () -> Platform.detect("Solaris", "sparcv9"));
  }
}
