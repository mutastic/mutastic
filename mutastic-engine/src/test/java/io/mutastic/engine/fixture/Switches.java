package io.mutastic.engine.fixture;

import java.io.IOException;
import java.io.Reader;

/// javac's code for a switch on a String, beside user code that looks like it: an `equals` the user
/// wrote, and a `close()` the user calls.
public class Switches {
  public int size(String name) {
    return switch (name) {
      case "small" -> 1;
      case "large" -> 3;
      default -> 2;
    };
  }

  public int isSmall(String name) {
    if (name.equals("small")) {
      return 1;
    }
    return 2;
  }

  public void done(Reader reader) throws IOException {
    reader.close();
  }
}
