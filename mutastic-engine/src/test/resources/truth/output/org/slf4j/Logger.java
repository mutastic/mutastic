package org.slf4j;

import org.slf4j.spi.LoggingEventBuilder;

public interface Logger {
  boolean isDebugEnabled();

  void debug(String format, Object... arguments);

  void info(String format, Object... arguments);

  LoggingEventBuilder atInfo();

  LoggingEventBuilder atDebug();
}
