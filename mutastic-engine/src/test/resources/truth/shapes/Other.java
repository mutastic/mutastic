package shapes;

/// Another class's static, which a card names with its class, as the source does.
final class Other {
  static int LIMIT = 1;

  private Other() {}

  /// A list whose size is -1 for a negative count: nothing stops a `List` of the project's own.
  static final class Sized extends java.util.AbstractList<Integer> {
    private final int size;

    Sized(int count) {
      this.size = count < 0 ? -1 : count;
    }

    @Override
    public Integer get(int index) {
      return index;
    }

    @Override
    public int size() {
      return size;
    }
  }

  /// Text whose length is -1 for a negative count.
  static final class Measured implements CharSequence {
    private final int length;

    Measured(int count) {
      this.length = count < 0 ? -1 : count;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      return 'x';
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return "";
    }
  }
}
