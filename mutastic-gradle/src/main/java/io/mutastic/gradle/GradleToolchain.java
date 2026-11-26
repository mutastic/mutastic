package io.mutastic.gradle;

import io.mutastic.api.BuildErrors;
import java.util.Optional;
import java.util.regex.Pattern;

/// The JDK a Gradle build's toolchain asks for, when Gradle found none: none on the machine, none
/// where it was told to look, and none it could download, with auto-provisioning off, no download
/// repository, or a download that failed. Gradle says each the same way, naming what it looked for:
/// `Cannot find a Java installation on your machine (Mac OS X 26.5 aarch64) matching:
/// {languageVersion=21, vendor=any vendor, implementation=vendor-specific,
/// nativeImageCapable=false}.` (Gradle 9), or `... matching this tasks requirements:
/// {languageVersion=21, vendor=ADOPTIUM, implementation=vendor-specific} for MAC_OS on aarch64.`
/// (Gradle 8).
final class GradleToolchain {
  private GradleToolchain() {}

  /// What Gradle looked for: the braces after `matching`.
  private static final Pattern NOT_FOUND =
      Pattern.compile(
          "Cannot find a Java installation on your machine[^{\\n]*\\{(?<requirements>[^}\\n]*)\\}");

  /// `languageVersion=21`.
  private static final Pattern VERSION = Pattern.compile("\\blanguageVersion=(?<version>\\d+)");

  /// `vendor=Eclipse Temurin`.
  private static final Pattern VENDOR = Pattern.compile("\\bvendor=(?<vendor>[^,]+)");

  /// The JDK Gradle couldn't find, from what it said: empty when that isn't why it failed.
  static Optional<BuildErrors.Toolchain> missing(String output) {
    var found = NOT_FOUND.matcher(output);
    if (!found.find()) {
      return Optional.empty();
    }
    var requirements = found.group("requirements");
    var version = VERSION.matcher(requirements);
    if (!version.find()) {
      return Optional.empty();
    }
    var vendor = VENDOR.matcher(requirements);
    var named =
        vendor.find() ? Optional.of(vendor.group("vendor").strip()) : Optional.<String>empty();
    return Optional.of(
        new BuildErrors.Toolchain(
            version.group("version"), named.filter(name -> !name.matches("any(?: vendor)?"))));
  }

  /// The argument that tells Gradle where a JDK is, to fill in.
  static String argument(BuildErrors.Toolchain jdk) {
    return "-Dorg.gradle.java.installations.paths=<path/to/jdk-%s>".formatted(jdk.version());
  }
}
