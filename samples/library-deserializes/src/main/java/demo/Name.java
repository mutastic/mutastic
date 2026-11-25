package demo;

import java.io.Serializable;

/// A name that travels as a serialized object.
public class Name implements Serializable {
  private static final long serialVersionUID = 1L;

  private final String first;

  public Name(String first) {
    this.first = first;
  }

  public String first() {
    return first;
  }

  public boolean isBlank() {
    return first.isBlank();
  }
}
