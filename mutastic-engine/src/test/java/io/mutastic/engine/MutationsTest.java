package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.fixture.Pricing;
import io.mutastic.report.SourceView;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutationsTest {
  private final Mutations mutations = new Mutations(ClassFile.of());

  @Test
  void findsTheBoundaryInAnIf() throws Exception {
    var mutants = mutations.find(model(Pricing.class));

    assertThat(mutants).singleElement().satisfies(mutant -> {
      assertThat(mutant.methodName()).isEqualTo("discount");
      assertThat(mutant.line()).isEqualTo(5);
      assertThat(mutant.original()).isEqualTo("IF_ICMPLT");
      assertThat(mutant.replacement()).isEqualTo("IF_ICMPLE");
      assertThat(SourceView.originalOperator(mutant)).isEqualTo(">=");
      assertThat(SourceView.mutatedOperator(mutant)).isEqualTo(">");
    });
  }

  @Test
  void theMutantVerifiesAndMovesTheEdge() throws Exception {
    var model = model(Pricing.class);
    var bytes = mutations.apply(model, mutations.find(model).getFirst());

    assertThat(mutations.verify(bytes)).isEmpty();
    var mutated = new SingleClassLoader(Pricing.class.getName(), bytes).loadClass(Pricing.class.getName());
    var discount = mutated.getMethod("discount", int.class);
    var pricing = mutated.getConstructor().newInstance();
    assertThat(discount.invoke(pricing, 100)).isEqualTo(0); // was 10
    assertThat(discount.invoke(pricing, 101)).isEqualTo(10); // unchanged
    assertThat(new Pricing().discount(100)).isEqualTo(10);
  }

  @Test
  void leavesTheCompilersEnumMethodsAlone() throws Exception {
    assertThat(mutations.find(model(Pricing.Tier.class))).isEmpty();
  }

  @Test
  void theIdIgnoresTheLine() {
    var here = new Mutant("com/example/Pricing", "discount", "(I)I", 0, 5, Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE");
    var moved = new Mutant("com/example/Pricing", "discount", "(I)I", 0, 9, Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE");
    var otherSite = new Mutant("com/example/Pricing", "discount", "(I)I", 1, 5, Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE");

    assertThat(moved.id()).isEqualTo(here.id());
    assertThat(otherSite.id()).isNotEqualTo(here.id());
    assertThat(List.of(here.id())).allMatch(id -> id.matches("m[0-9a-f]{8}"));
  }

  static ClassModel model(Class<?> type) throws Exception {
    try (var in = type.getResourceAsStream(type.getName().substring(type.getName().lastIndexOf(".") + 1) + ".class")) {
      return ClassFile.of().parse(in.readAllBytes());
    }
  }

  /// Defines one class from the given bytes, and leaves everything else to its parent.
  static final class SingleClassLoader extends ClassLoader {
    private final String name;
    private final byte[] bytes;

    SingleClassLoader(String name, byte[] bytes) {
      super(MutationsTest.class.getClassLoader());
      this.name = name;
      this.bytes = bytes;
    }

    @Override
    protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
      if (!requested.equals(name)) {
        return super.loadClass(requested, resolve);
      }
      synchronized (getClassLoadingLock(requested)) {
        var loaded = findLoadedClass(requested);
        return loaded != null ? loaded : defineClass(requested, bytes, 0, bytes.length);
      }
    }
  }
}
