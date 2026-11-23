package ch.qos.logback.classic;

public abstract class Logger implements org.slf4j.Logger {
  public abstract void warn(String message);
}
