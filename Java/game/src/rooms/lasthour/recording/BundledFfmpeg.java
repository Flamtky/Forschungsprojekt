package rooms.lasthour.recording;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Locates and verifies the FFmpeg executable matching the current operating system. */
final class BundledFfmpeg {
  static final String PATH_PROPERTY = "lasthour.recording.ffmpegPath";
  static final String BUNDLE_VERSION = "b6.1.1";

  private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(10L);

  private BundledFfmpeg() {}

  static ResolvedFfmpeg resolve() throws IOException {
    Platform platform = Platform.current();
    String override = System.getProperty(PATH_PROPERTY, "").trim();
    if (!override.isBlank()) {
      return verifyFile(Path.of(override), platform, "system-property");
    }

    for (Path candidate : candidates(platform)) {
      if (Files.isRegularFile(candidate)) {
        return verifyFile(candidate, platform, "bundled");
      }
    }

    try {
      return verifyCommand(platform.executableName(), platform, "PATH");
    } catch (IOException exception) {
      throw new IOException(
          "No usable FFmpeg executable for "
              + platform.id()
              + ". Expected tools/ffmpeg/"
              + platform.id()
              + "/"
              + platform.executableName()
              + " or property "
              + PATH_PROPERTY,
          exception);
    }
  }

  private static List<Path> candidates(Platform platform) {
    Set<Path> roots = new LinkedHashSet<>();
    Path workingDirectory = Path.of("").toAbsolutePath().normalize();
    roots.add(workingDirectory);
    roots.add(workingDirectory.resolve("game"));

    codeLocation().ifPresent(roots::add);
    codeLocation().map(Path::getParent).ifPresent(roots::add);

    List<Path> candidates = new ArrayList<>();
    for (Path root : roots) {
      candidates.add(
          root.resolve("tools")
              .resolve("ffmpeg")
              .resolve(platform.id())
              .resolve(platform.executableName()));
      candidates.add(
          root.resolve("local-tools")
              .resolve("ffmpeg")
              .resolve(platform.id())
              .resolve(platform.executableName()));
    }
    return candidates;
  }

  private static java.util.Optional<Path> codeLocation() {
    try {
      Path location =
          Path.of(BundledFfmpeg.class.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toAbsolutePath()
              .normalize();
      return java.util.Optional.of(Files.isDirectory(location) ? location : location.getParent());
    } catch (URISyntaxException | RuntimeException exception) {
      return java.util.Optional.empty();
    }
  }

  private static ResolvedFfmpeg verifyFile(Path executable, Platform platform, String source)
      throws IOException {
    Path normalized = executable.toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalized)) {
      throw new IOException("FFmpeg executable does not exist: " + normalized);
    }
    if (!platform.windows() && !Files.isExecutable(normalized)) {
      normalized.toFile().setExecutable(true, true);
    }
    if (!platform.windows() && !Files.isExecutable(normalized)) {
      throw new IOException("FFmpeg executable is not executable: " + normalized);
    }
    return verifyCommand(normalized.toString(), platform, source);
  }

  private static ResolvedFfmpeg verifyCommand(String command, Platform platform, String source)
      throws IOException {
    Process process =
        new ProcessBuilder(command, "-hide_banner", "-version").redirectErrorStream(true).start();
    boolean finished;
    try {
      finished = process.waitFor(VERIFY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while checking FFmpeg", exception);
    }
    if (!finished) {
      process.destroyForcibly();
      throw new IOException("FFmpeg version check timed out after 10 seconds");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IOException(
          "FFmpeg version check failed with exit code "
              + process.exitValue()
              + ": "
              + firstLine(output));
    }
    return new ResolvedFfmpeg(command, platform, source, firstLine(output));
  }

  private static String firstLine(String output) {
    return output.lines().findFirst().orElse("unknown FFmpeg version").trim();
  }

  record ResolvedFfmpeg(String command, Platform platform, String source, String version) {}

  enum Platform {
    WINDOWS_X86_64("windows-x86_64", "ffmpeg.exe", true),
    LINUX_X86_64("linux-x86_64", "ffmpeg", false),
    LINUX_AARCH64("linux-aarch64", "ffmpeg", false),
    MACOS_X86_64("macos-x86_64", "ffmpeg", false),
    MACOS_AARCH64("macos-aarch64", "ffmpeg", false);

    private final String id;
    private final String executableName;
    private final boolean windows;

    Platform(String id, String executableName, boolean windows) {
      this.id = id;
      this.executableName = executableName;
      this.windows = windows;
    }

    static Platform current() {
      return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Platform detect(String osName, String architecture) {
      String os = osName.toLowerCase(Locale.ROOT);
      String arch = architecture.toLowerCase(Locale.ROOT);
      boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
      boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");

      if (os.contains("win") && x64) {
        return WINDOWS_X86_64;
      }
      if (os.contains("linux") && x64) {
        return LINUX_X86_64;
      }
      if (os.contains("linux") && arm64) {
        return LINUX_AARCH64;
      }
      if ((os.contains("mac") || os.contains("darwin")) && x64) {
        return MACOS_X86_64;
      }
      if ((os.contains("mac") || os.contains("darwin")) && arm64) {
        return MACOS_AARCH64;
      }
      throw new IllegalStateException(
          "Unsupported FFmpeg platform: os.name=" + osName + ", os.arch=" + architecture);
    }

    String id() {
      return id;
    }

    String executableName() {
      return executableName;
    }

    boolean windows() {
      return windows;
    }
  }
}
