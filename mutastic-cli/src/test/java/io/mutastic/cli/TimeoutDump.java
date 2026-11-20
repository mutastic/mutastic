package io.mutastic.cli;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.PreInterruptCallback;
import org.junit.jupiter.api.extension.PreInterruptContext;

/// What every thread and process was doing when a test ran out of time, before JUnit interrupts it:
/// the CLI's tests have a time limit (the default timeout in the build file) so that a test that
/// hangs fails and says where, rather than holding the job until CI cuts it off with no log. It
/// writes the test JVM's threads with their locks, every process this JVM started, down the tree,
/// and every other JVM's threads (`jcmd Thread.print`: the samples' Gradle daemons and Maven), to a
/// file in `mutastic.timeoutDumps`, which CI prints and keeps with the reports.
///
/// Found by JUnit from the service file, as the build file asks it to.
public final class TimeoutDump implements PreInterruptCallback {
  /// The most other JVMs dumped, and how long each `jcmd` may take, so the dump ends.
  private static final int JVMS = 12;

  private static final Duration JCMD = Duration.ofSeconds(20);

  @Override
  public void beforeThreadInterrupt(PreInterruptContext context, ExtensionContext extension)
      throws IOException {
    var name = extension.getUniqueId();
    var text = new StringBuilder();
    text.append("Timed out: ")
        .append(extension.getDisplayName())
        .append(" (")
        .append(name)
        .append(") at ")
        .append(Instant.now())
        .append("\nThe thread to be interrupted: ")
        .append(context.getThreadToInterrupt().getName())
        .append("\n\n");
    threads(text);
    processes(text);
    var directory = System.getProperty("mutastic.timeoutDumps");
    var file = Path.of(directory == null ? "build/reports/timeouts" : directory);
    Files.createDirectories(file);
    var dump = file.resolve(name.replaceAll("[^A-Za-z0-9.-]+", "_") + ".txt");
    Files.writeString(dump, text, StandardCharsets.UTF_8);
    System.err.println("A test ran out of time: what was running is in " + dump);
  }

  /// This JVM's threads, each with its whole stack and the locks it holds and waits on.
  private static void threads(StringBuilder text) {
    text.append("--- This JVM's threads (pid ").append(ProcessHandle.current().pid()).append(")\n");
    for (var thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
      thread(text, thread);
    }
  }

  private static void thread(StringBuilder text, ThreadInfo thread) {
    text.append('"')
        .append(thread.getThreadName())
        .append("\" #")
        .append(thread.getThreadId())
        .append(thread.isDaemon() ? " daemon " : " ")
        .append(thread.getThreadState());
    if (thread.getLockName() != null) {
      text.append(" on ").append(thread.getLockName());
    }
    if (thread.getLockOwnerName() != null) {
      text.append(" owned by \"").append(thread.getLockOwnerName()).append('"');
    }
    text.append('\n');
    var stack = thread.getStackTrace();
    for (int frame = 0; frame < stack.length; frame++) {
      text.append("\tat ").append(stack[frame]).append('\n');
      for (var monitor : thread.getLockedMonitors()) {
        if (monitor.getLockedStackDepth() == frame) {
          text.append("\t- locked ").append(monitor).append('\n');
        }
      }
    }
    for (var lock : thread.getLockedSynchronizers()) {
      text.append("\t- holds ").append(lock).append('\n');
    }
    text.append('\n');
  }

  /// The processes this JVM started, down the tree, then every other JVM's threads: those it
  /// started first, then the newest (a sample's Gradle daemon isn't this JVM's child).
  private static void processes(StringBuilder text) {
    var self = ProcessHandle.current();
    text.append("--- The processes this JVM started\n");
    self.children().forEach(child -> tree(text, child, 0));
    text.append("\n--- Every other JVM\n");
    List<ProcessHandle> jvms = new ArrayList<>();
    self.descendants().filter(TimeoutDump::isJava).forEach(jvms::add);
    ProcessHandle.allProcesses()
        .filter(process -> process.pid() != self.pid() && isJava(process))
        .filter(process -> !jvms.contains(process))
        .sorted(
            Comparator.comparing(
                    (ProcessHandle process) -> process.info().startInstant().orElse(Instant.MIN))
                .reversed())
        .forEach(jvms::add);
    for (var jvm : jvms) {
      text.append(describe(jvm)).append('\n');
    }
    for (var jvm : jvms.subList(0, Math.min(JVMS, jvms.size()))) {
      text.append("\n--- jcmd ").append(jvm.pid()).append(" Thread.print\n");
      text.append(jcmd(jvm.pid()));
    }
  }

  private static void tree(StringBuilder text, ProcessHandle process, int depth) {
    text.append("  ".repeat(depth)).append(describe(process)).append('\n');
    process.children().forEach(child -> tree(text, child, depth + 1));
  }

  /// Its pid, parent, when it started, its CPU time and its command line, as far as the system says
  /// (Windows often gives only the program).
  private static String describe(ProcessHandle process) {
    var info = process.info();
    return "%d (parent %s, started %s, cpu %s): %s"
        .formatted(
            process.pid(),
            process.parent().map(parent -> Long.toString(parent.pid())).orElse("?"),
            info.startInstant().map(Instant::toString).orElse("?"),
            info.totalCpuDuration().map(Duration::toString).orElse("?"),
            info.commandLine().or(info::command).orElse("?"));
  }

  private static boolean isJava(ProcessHandle process) {
    var command = process.info().command().orElse("");
    return command.endsWith("java") || command.endsWith("java.exe");
  }

  /// `jcmd <pid> Thread.print -l`, or why it couldn't be had.
  private static String jcmd(long pid) {
    var windows = java.io.File.separatorChar == '\\';
    var jcmd = Path.of(System.getProperty("java.home"), "bin", windows ? "jcmd.exe" : "jcmd");
    try {
      var out = Files.createTempFile("jcmd", ".txt");
      try {
        var process =
            new ProcessBuilder(jcmd.toString(), Long.toString(pid), "Thread.print", "-l")
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
        if (!process.waitFor(JCMD.toSeconds(), TimeUnit.SECONDS)) {
          process.destroyForcibly();
          return "jcmd didn't answer in " + JCMD.toSeconds() + " seconds\n";
        }
        return Files.readString(out, StandardCharsets.UTF_8);
      } finally {
        Files.deleteIfExists(out);
      }
    } catch (IOException e) {
      return "jcmd failed: " + e + "\n";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted waiting for jcmd\n";
    }
  }
}
