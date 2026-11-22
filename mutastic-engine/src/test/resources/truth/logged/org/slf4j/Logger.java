package org.slf4j;

/// slf4j's `Logger`, as much of it as the fixtures call: compiled against, never run.
public interface Logger {
  void info(String message);

  void info(String format, Object argument);

  void info(String format, Object first, Object second);

  void info(String format, Object... arguments);

  void debug(String format, Object argument);

  void warn(String format, Object argument);
}
