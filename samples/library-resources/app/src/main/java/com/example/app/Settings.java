package com.example.app;

/// The app's settings, read once: there's no default port.
public final class Settings {
  static final int PORT = Integer.parseInt(System.getProperty("app.port"));

  private Settings() {}

  public static boolean privileged() {
    return PORT < 1024;
  }
}
