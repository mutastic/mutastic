package io.proptastic;

/// proptastic's entry point, as its contract names it, and only the words this sample's tests use:
/// `forAll`, `integers` and `lists`. mutastic finds proptastic by this class on the test classpath.
///
/// A fixture, not proptastic: it reproduces the surface mutastic depends on, so mutastic's public
/// build can check it without the library, which isn't published yet.
///
/// - **Settings,** read as each property starts: `proptastic.seed`, else the environment's
///   `PROPTASTIC_SEED`, `0x` and hex digits only (a bare number is refused); `proptastic.tries`;
///   `proptastic.shrink=false` reports the first counterexample; `proptastic.events=false` writes
///   no event; `proptastic.replay=false` saves no failure; `proptastic.dir`, where they go,
///   `build/proptastic` unless it says; `proptastic.corpus=off` leaves the corpus,
///   `.proptastic/corpus`, alone.
/// - **The random stream** is keyed on the test method's signature, never its class.
/// - **The failure** is `io.proptastic.core.PropertyFailure`: the assertion's own message, a blank
///   line, then the report, its keys right-aligned, stacked when a value spans lines or passes the
///   width (`COLUMNS`, else 100).
///
/// Unlike proptastic, it names its draws by their place (`a`, `b`), not as the lambda does, and
/// keeps no corpus entries: it only writes each property's description there, as proptastic does.
public final class Proptastic {
  private Proptastic() {}

  /// A property over one domain.
  public static <A> Property1<A> forAll(Domain<A> a) {
    return new Property1<>(a);
  }

  /// Every `int`, unless bounded.
  public static IntegerDomain integers() {
    return new IntegerDomain(Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  /// Lists of up to 20 of `elements`'s values.
  public static <T> ListDomain<T> lists(Domain<T> elements) {
    return new ListDomain<>(elements);
  }
}
