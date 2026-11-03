package io.mutastic.engine.fixture;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// A local with a type annotation, as in mutastic's own `SourceView.onlyPlaceOf`: javac records
/// where in the code the local lives, by bytecode offset, so a mutant that changes the code's
/// length must not leave those offsets behind.
public class Tail {
  public record Place(int line, int start, int end) {}

  public static Optional<Place> onlyPlaceOf(
      String token, boolean call, List<String> all, int first, int last) {
    @Nullable Place found = null;
    int count = 0;
    for (int number = first; number <= last; number++) {
      var text = all.get(number - 1);
      for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
        count++;
        found =
            call
                ? new Place(number, at + 1, at + token.length())
                : token.isEmpty()
                    ? new Place(number, at + 1, text.stripTrailing().length() + 1)
                    : new Place(number, at + 2, at + token.length());
      }
    }
    return count == 1 && found != null ? Optional.of(found) : Optional.empty();
  }
}
