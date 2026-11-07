package io.mutastic.engine;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.api.Project;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.LineNumber;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/// The mutants of the operators that change a class as a whole ([ClassMutator]), which have no site
/// among its instructions and no line in its class file: their line comes from the source, for what
/// a card shows and what an ignore holds to.
final class WholeClasses {
  private static final int NO_LINE = -1;

  /// How far below an annotation, in lines, the thing it's on may be declared.
  private static final int DECLARATION_WITHIN = 8;

  private static final int DEFAULT_ORDINAL = 0;

  private final List<ClassMutator> mutators;
  private final BiFunction<ClassModel, ClassMutator.Member, Optional<String>> decided;
  private List<java.nio.file.Path> sources = List.of();
  private boolean frameworkLoadsTheApp;

  /// @param decided what the project itself says of a member
  WholeClasses(
      List<Operator> operators,
      BiFunction<ClassModel, ClassMutator.Member, Optional<String>> decided) {
    this.mutators =
        operators.stream()
            .filter(ClassMutator.class::isInstance)
            .map(ClassMutator.class::cast)
            .toList();
    this.decided = decided;
  }

  void prepareFor(Project project) {
    sources = project.sources();
    frameworkLoadsTheApp = TestFrameworks.loadsTheApp(project.dependencies());
    mutators.forEach(mutator -> mutator.prepare(project));
  }

  List<Mutant> find(ClassModel model) {
    var mutants = new ArrayList<Mutant>();
    for (ClassMutator mutator : mutators) {
      for (ClassMutator.Change change : mutator.changes(model)) {
        mutants.add(mutant(model, mutator, change));
      }
    }
    return mutants;
  }

  static byte[] apply(ClassModel model, Mutant mutant, ClassMutator mutator) {
    return mutator.write(
        model,
        new ClassMutator.Member(mutant.methodName(), mutant.descriptor()),
        mutant.replacement());
  }

  private Mutant mutant(ClassModel model, ClassMutator mutator, ClassMutator.Change change) {
    var skipped =
        change
            .skipped()
            .or(this::inAFrameworkThatLoadsTheApp)
            .or(() -> decided.apply(model, change.member()));
    return new Mutant(
        model.thisClass().asInternalName(),
        change.member().name(),
        change.member().descriptor(),
        DEFAULT_ORDINAL,
        lineOf(model, change),
        mutator,
        change.original(),
        change.label(),
        skipped);
  }

  /// A framework that loads the app itself reads what it reads as it builds the app, once, and a
  /// class it loaded can only have its methods' code swapped: the change can't reach it, and every
  /// mutant would look missed.
  private Optional<String> inAFrameworkThatLoadsTheApp() {
    return frameworkLoadsTheApp
        ? Optional.of(
            "unviable: this project's tests run in a framework that loads the app itself, and a"
                + " change to a class as a whole can't reach what it has read")
        : Optional.empty();
  }

  /// The change's line in the source, when its text marks one: for a method with code, the last
  /// mention at or before where its code starts; for anything else, the first mention with the
  /// member's name declared in the lines after it.
  private int lineOf(ClassModel model, ClassMutator.Change change) {
    if (change.sourceText().isEmpty()) {
      return firstCodeLine(model, change.member()).orElse(NO_LINE);
    }
    var path = SourcePaths.of(model, sources);
    if (path.isEmpty()) {
      return NO_LINE;
    }
    try {
      var lines = Files.readAllLines(path.get());
      var firstCodeLine = firstCodeLine(model, change.member());
      return firstCodeLine.isPresent()
          ? lastMentionUpTo(lines, change.sourceText(), firstCodeLine.get())
          : firstMentionBefore(lines, change.sourceText(), declaredName(model, change.member()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Optional<Integer> firstCodeLine(ClassModel model, ClassMutator.Member member) {
    return model.methods().stream()
        .filter(
            method ->
                method.methodName().equalsString(member.name())
                    && method.methodType().equalsString(member.descriptor()))
        .flatMap(method -> method.code().stream())
        .flatMap(code -> code.elementStream())
        .filter(LineNumber.class::isInstance)
        .map(element -> ((LineNumber) element).line())
        .min(Integer::compare);
  }

  private static int lastMentionUpTo(List<String> lines, String text, int line) {
    for (int at = Math.min(line, lines.size()); at >= 1; at--) {
      if (mentions(lines.get(at - 1), text)) {
        return at;
      }
    }
    return NO_LINE;
  }

  /// The first mention on what declares `declared`: the name comes between it and the end of the
  /// declaration, or for a parameter of a method with no code, before it on its line.
  private static int firstMentionBefore(List<String> lines, String text, String declared) {
    var name = Pattern.compile("\\b" + Pattern.quote(declared) + "\\b");
    for (int at = 0; at < lines.size(); at++) {
      if (mentions(lines.get(at), text)) {
        var line = lines.get(at);
        var fromIt =
            line.substring(line.indexOf(text)) + "\n" + String.join("\n", linesAfter(lines, at));
        var beforeIt = line.substring(0, line.indexOf(text));
        if (name.matcher(toEndOfDeclaration(fromIt)).find()
            || (beforeIt.contains("(") && name.matcher(beforeIt).find())) {
          return at + 1;
        }
      }
    }
    return NO_LINE;
  }

  private static List<String> linesAfter(List<String> lines, int at) {
    return lines.subList(at + 1, Math.min(at + DECLARATION_WITHIN, lines.size()));
  }

  /// The text up to the first `;` or `{` outside parentheses, so that an annotation's own arguments
  /// don't end it, and without what's in those parentheses, so that they don't name a member.
  private static String toEndOfDeclaration(String text) {
    var declaration = new StringBuilder();
    int depth = 0;
    for (int at = 0; at < text.length(); at++) {
      char each = text.charAt(at);
      if (each == '(') {
        depth++;
      } else if (each == ')') {
        depth--;
      } else if (depth == 0) {
        if (each == ';' || each == '{') {
          break;
        }
        declaration.append(each);
      }
    }
    return declaration.toString();
  }

  /// `@Size` in `@Size(max = 3)`, and not in `@SizeLimit`.
  private static boolean mentions(String line, String text) {
    int at = line.indexOf(text);
    return at >= 0
        && (at + text.length() == line.length()
            || !Character.isJavaIdentifierPart(line.charAt(at + text.length())));
  }

  /// The name the source declares the member by: a class's, as `Outer$Inner` is `Inner`.
  private static String declaredName(ClassModel model, ClassMutator.Member member) {
    if (!member.isClass()) {
      return member.simpleName();
    }
    var internal = model.thisClass().asInternalName();
    return internal.substring(Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$')) + 1);
  }
}
