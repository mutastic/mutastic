package io.mutastic.gradle.plugin;

/// Something shown while the command runs, taken away when closed: Gradle's progress line, or
/// nothing where Gradle has none to give.
interface Shown extends AutoCloseable {
  @Override
  void close();
}
