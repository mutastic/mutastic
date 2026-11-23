package org.apache.logging.log4j;

public interface LogBuilder {
  LogBuilder withThrowable(Throwable throwable);

  void log(String message, Object... parameters);
}
