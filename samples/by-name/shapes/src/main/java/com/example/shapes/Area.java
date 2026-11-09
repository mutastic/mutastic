package com.example.shapes;

/// A square's area. The fast one lives in another module, found by name when it's on the
/// classpath, as a plugin would be.
public interface Area {
  int of(int side);

  static Area fastest() {
    try {
      return (Area) Class.forName("com.example.fast.FastArea").getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      return side -> side * side;
    }
  }
}
