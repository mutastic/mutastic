package org.apache.logging.log4j;

public interface Logger {
  boolean isDebugEnabled();

  void debug(String message, Object... parameters);

  LogBuilder atInfo();
}
