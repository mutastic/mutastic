package org.slf4j.spi;

public interface LoggingEventBuilder {
  LoggingEventBuilder addArgument(Object argument);

  LoggingEventBuilder setMessage(String message);

  void log(String message);

  void log();
}
