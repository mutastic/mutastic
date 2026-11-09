package com.example.control;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public final class Reader {
  /// Where text comes from: a file, or a server that may not answer in time.
  public interface Source {
    String read() throws IOException, TimeoutException;
  }

  /// What `source` says, or `cached` when it can't be read.
  public static String load(Source source, String cached) {
    try {
      return source.read();
    } catch (IOException | TimeoutException e) {
      return cached;
    }
  }
}
