package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ResourceBundle;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/// Named for a JDK service in the tests' resources, so the worker shares this class by its own name
/// between runs, statics and all; it counts its runs in one.
public class SharedTest extends System.LoggerFinder {
  private static int runs;

  /// Slower than BothTest, so a mutant both reach runs BothTest first, then this.
  @Test
  void runsOnce() throws InterruptedException {
    Thread.sleep(200);
    runs++;
    assertEquals(1, runs);
    assertEquals(190, Shop.shared(200));
    assertEquals(190, Shop.both(200));
  }

  @Override
  public System.Logger getLogger(String name, Module module) {
    return new Quiet(name);
  }

  /// Logs nothing.
  private record Quiet(String getName) implements System.Logger {
    @Override
    public boolean isLoggable(Level level) {
      return false;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {}

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {}

    @Override
    public void log(Level level, Supplier<String> message) {}
  }
}
