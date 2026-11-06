package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MutantInPlaceTest {
  private static final byte[] BYTES = new byte[0];

  /// Hears what's swapped, in order.
  private static final class Recording implements MutantInPlace.Classes {
    final List<String> swaps = new ArrayList<>();

    @Override
    public void swapIn(Map<String, byte[]> mutants) {
      if (!mutants.isEmpty()) {
        swaps.add("in " + new TreeSet<>(mutants.keySet()));
      }
    }

    @Override
    public void swapBack(Set<String> names) {
      if (!names.isEmpty()) {
        swaps.add("back " + new TreeSet<>(names));
      }
    }
  }

  private final Recording classes = new Recording();
  private final MutantInPlace inPlace = new MutantInPlace();

  @Test
  void theNextMutantOfAClassGoesStraightInItsPlace() throws Exception {
    inPlace.put(classes, Map.of("A", BYTES));
    inPlace.put(classes, Map.of("A", BYTES));

    assertThat(classes.swaps).containsExactly("in [A]", "in [A]");
  }

  /// A mutant of one class must never be there while the next mutant's tests run.
  @Test
  void aMutantOfAnotherClassRestoresTheLastFirst() throws Exception {
    inPlace.put(classes, Map.of("A", BYTES));
    inPlace.put(classes, Map.of("B", BYTES));

    assertThat(classes.swaps).containsExactly("in [A]", "back [A]", "in [B]");
  }

  /// A run with no classes is of the code as built: the baseline a flaky test is checked against.
  @Test
  void aRunOfTheCodeAsBuiltRestoresIt() throws Exception {
    inPlace.put(classes, Map.of("A", BYTES));
    inPlace.put(classes, Map.of());

    assertThat(classes.swaps).containsExactly("in [A]", "back [A]");
  }

  @Test
  void restoringPutsBackOnlyWhatIsInPlaceAndOnce() throws Exception {
    inPlace.put(classes, Map.of("A", BYTES));
    inPlace.restore(classes);
    inPlace.restore(classes);

    assertThat(classes.swaps).containsExactly("in [A]", "back [A]");
  }

  @Test
  void aMutantRestoredIsNotRestoredAgainByTheNext() throws Exception {
    inPlace.put(classes, Map.of("A", BYTES));
    inPlace.restore(classes);
    inPlace.put(classes, Map.of("B", BYTES));

    assertThat(classes.swaps).containsExactly("in [A]", "back [A]", "in [B]");
  }
}
