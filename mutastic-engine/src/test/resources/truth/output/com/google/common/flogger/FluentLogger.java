package com.google.common.flogger;

public abstract class FluentLogger {
  public interface Api {
    Api withCause(Throwable cause);

    void log(String message, Object argument);
  }

  public abstract Api atInfo();
}
