package io.mutastic.plugins;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import io.mutastic.api.Project;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/// Rewrites of a class, proposed as Java source by whatever proposes them (a person, or a model)
/// and kept in the project: each file in `.mutastic/proposed/<class's binary name>/` is what the
/// class's source file would be instead, and the file's name, without `.java`, is the change's
/// label. So a proposal's id stays as long as its file does, and a run needs no model, no network
/// and no key.
///
/// A proposal is only a mutant once it has been through these, each of which skips it with the
/// reason, kept in the report and out of the score:
///
/// - it compiles, against the project's own classes and libraries, for the class's Java release;
/// - it has the shape of the class: the same fields and methods, and no more classes;
/// - it changes exactly one method, so a card can say which, and which tests reach it;
/// - it compiles to something else than the class does, and than the proposals before it do.
///
/// What's left may still do the same as the class in a way only running it could show: no filter
/// can rule that out, so the card says so.
public final class ProposedRewrites implements ClassMutator {
  private static final String DIRECTORY = ".mutastic/proposed";
  private static final String EXTENSION = ".java";

  private Path proposals = Path.of(DIRECTORY);
  private final List<Path> sources = new ArrayList<>();
  private final List<Path> classpath = new ArrayList<>();
  private final Map<String, Map<String, byte[]>> bytes = new HashMap<>();

  @Override
  public String id() {
    return "proposed/rewrite";
  }

  @Override
  public String explanation() {
    return "A method is rewritten as a proposal in .mutastic/proposed says. It goes unnoticed when"
        + " no test tells the rewrite from the method, and it may do the same thing.";
  }

  @Override
  public Reach reach() {
    return Reach.METHOD;
  }

  @Override
  public void prepare(Project project) {
    proposals = project.workingDirectory().resolve(DIRECTORY);
    sources.clear();
    sources.addAll(project.sources());
    classpath.clear();
    classpath.addAll(project.classes());
    classpath.addAll(project.alongside());
    classpath.addAll(project.dependencies());
  }

  @Override
  public List<Change> changes(ClassModel owner) {
    var binaryName = owner.thisClass().asInternalName().replace('/', '.');
    var directory = proposals.resolve(binaryName);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    var compiler = new ProposalCompiler(owner, sources, classpath);
    var written = new HashMap<String, byte[]>();
    var firstWith = new HashMap<String, String>();
    var changes = new ArrayList<Change>();
    for (Path file : files(directory)) {
      var label = label(file);
      var judged = compiler.judge(read(file));
      judged.bytes().ifPresent(compiled -> written.put(label, compiled));
      changes.add(change(judged, label, judged.fingerprint().map(firstWith::get)));
      judged.fingerprint().ifPresent(fingerprint -> firstWith.putIfAbsent(fingerprint, label));
    }
    bytes.put(binaryName, written);
    return changes;
  }

  /// The change a proposal is, unless a proposal before it compiled to the same.
  private static Change change(
      ProposalCompiler.Judged judged, String label, Optional<String> sameAs) {
    var skipped =
        judged
            .skipped()
            .or(() -> sameAs.map("equivalent: compiles to the same code as `%s`"::formatted));
    return new Change(judged.member(), label, "the method as written", "", skipped);
  }

  private static List<Path> files(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(file -> file.toString().endsWith(EXTENSION)).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String label(Path file) {
    var name = file.getFileName().toString();
    return name.substring(0, name.length() - EXTENSION.length());
  }

  @Override
  public byte[] write(ClassModel owner, Member member, String label) {
    var binaryName = owner.thisClass().asInternalName().replace('/', '.');
    return Optional.ofNullable(bytes.getOrDefault(binaryName, Map.of()).get(label))
        .orElseThrow(() -> new IllegalStateException("no compiled proposal called " + label));
  }

  @Override
  public Mutator.Words words(Mutant mutant) {
    var file =
        "%s/%s/%s%s".formatted(DIRECTORY, mutant.binaryName(), mutant.replacement(), EXTENSION);
    return new Mutator.Words(
        "rewritten as `%s` proposes".formatted(file),
        "no test tells `%s()` from the rewrite in `%s`".formatted(mutant.methodName(), file),
        "read `%s`: if it changes what `%s()` does, add a test it fails; if it does the same, delete it"
            .formatted(file, mutant.methodName()));
  }
}
