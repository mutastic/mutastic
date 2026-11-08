package io.mutastic.api.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.DocumentationTool;
import javax.tools.ToolProvider;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/// The docs' reference of the API plugins are written against, from its own doc comments: each
/// package, its public types and their members, as JSON for docs.mutastic.io to render. Comments
/// are Markdown already; a reference to a type or member (`[Mutator]`, `[#changes(Site)]`) becomes
/// a link to its page.
///
/// Run as `ApiModel <out.json> <classpath> <source directory>...`: it runs the JDK's javadoc with
/// itself as the doclet, so no standard-doclet option gets in the way.
public final class ApiModel implements Doclet {
  /// `[Mutator]`, `[Mutator.Site]`, `[#changes(Site)]`, `[java.util.ServiceLoader]`: a reference,
  /// not a Markdown link (`[text](url)`) or a link's text.
  private static final Pattern REFERENCE =
      Pattern.compile(
          "(?<![\\]\\\\`])\\[([A-Za-z_][\\w.]*)?(#\\w+(?:\\([^)\\]]*\\))?)?\\](?![(\\[])");

  /// `java.util.List` → `List`: package names left out of signatures.
  private static final Pattern QUALIFIER = Pattern.compile("\\b(?:[a-z][a-z0-9_]*\\.)+(?=[A-Z@])");

  private static final Pattern SENTENCE_END = Pattern.compile("[.!?](\\s|$)");

  private static final Pattern TAG = Pattern.compile("^@(param|return|throws)\\s*(\\S*)\\s*(.*)$");

  private static Path out = Path.of("api.json");

  private Elements elements;

  /// Every documented type, by its name in its package (`Mutator.Site`), to its page's slug.
  private final Map<String, String> slugs = new LinkedHashMap<>();

  public static void main(String[] args) {
    out = Path.of(args[0]);
    DocumentationTool javadoc = ToolProvider.getSystemDocumentationTool();
    var files = javadoc.getStandardFileManager(null, Locale.ROOT, null);
    var sources =
        files.getJavaFileObjectsFromPaths(
            Stream.of(args).skip(2).flatMap(ApiModel::javaFiles).toList());
    var options = List.of("-classpath", args[1], "-quiet");
    boolean done = javadoc.getTask(null, files, null, ApiModel.class, options, sources).call();
    if (!done) {
      throw new IllegalStateException("javadoc couldn't read the API's sources");
    }
  }

  private static Stream<Path> javaFiles(String directory) {
    try (Stream<Path> walk = Files.walk(Path.of(directory))) {
      return walk.filter(path -> path.toString().endsWith(".java")).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void init(Locale locale, Reporter reporter) {}

  @Override
  public String getName() {
    return "mutastic-api-model";
  }

  @Override
  public Set<? extends Option> getSupportedOptions() {
    return Set.of();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latest();
  }

  @Override
  public boolean run(DocletEnvironment environment) {
    elements = environment.getElementUtils();
    var packages =
        ElementFilter.packagesIn(environment.getIncludedElements()).stream()
            .sorted(Comparator.comparing(each -> each.getQualifiedName().toString()))
            .toList();
    var types = new LinkedHashMap<PackageElement, List<TypeElement>>();
    for (PackageElement pkg : packages) {
      var inPackage = new ArrayList<TypeElement>();
      ElementFilter.typesIn(pkg.getEnclosedElements()).forEach(type -> addPublic(type, inPackage));
      inPackage.sort(Comparator.comparing(this::nameInPackage));
      inPackage.forEach(type -> slugs.put(nameInPackage(type), slug(nameInPackage(type))));
      types.put(pkg, inPackage);
    }
    var model = new ArrayList<Object>();
    types.forEach((pkg, inPackage) -> model.add(pkg(pkg, inPackage)));
    try {
      Files.createDirectories(out.toAbsolutePath().getParent());
      Files.writeString(out, Json.write(Map.of("packages", model)) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

  /// `type` and its public nested types, in order.
  private void addPublic(TypeElement type, List<TypeElement> into) {
    if (isPublic(type)) {
      into.add(type);
      ElementFilter.typesIn(type.getEnclosedElements()).forEach(nested -> addPublic(nested, into));
    }
  }

  private static boolean isPublic(Element element) {
    return element.getModifiers().contains(Modifier.PUBLIC)
        || element.getEnclosingElement().getKind().isInterface();
  }

  private Map<String, Object> pkg(PackageElement pkg, List<TypeElement> types) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("name", pkg.getQualifiedName().toString());
    fields.put("doc", markdown(doc(pkg).text(), ""));
    fields.put("types", types.stream().map(this::type).toList());
    return fields;
  }

  private Map<String, Object> type(TypeElement type) {
    var name = nameInPackage(type);
    var doc = doc(type);
    var fields = new LinkedHashMap<String, Object>();
    fields.put("name", name);
    fields.put("slug", slugs.get(name));
    fields.put("kind", kind(type));
    fields.put("signature", Signatures.of(type));
    fields.put("summary", markdown(doc.summary(), "../"));
    fields.put("doc", markdown(doc.text(), "../"));
    fields.put("params", tags(doc.params(), "../"));
    var members = new ArrayList<Object>();
    var anchors = new LinkedHashMap<String, Integer>();
    for (Element member : type.getEnclosedElements()) {
      if (documented(type, member)) {
        members.add(member(member, anchors));
      }
    }
    fields.put("members", members);
    return fields;
  }

  /// A member written in the source and public: not a record's own accessors and canonical
  /// constructor, an enum's `values()`, or a nested type, which has a page of its own.
  private boolean documented(TypeElement type, Element member) {
    if (!isPublic(member) || elements.getOrigin(member) != Elements.Origin.EXPLICIT) {
      return false;
    }
    return switch (member.getKind()) {
      case METHOD -> !implicit(type, (ExecutableElement) member);
      case FIELD, ENUM_CONSTANT -> true;
      case CONSTRUCTOR -> !isCanonical(type, (ExecutableElement) member);
      default -> false;
    };
  }

  /// What every record and enum has, unless the source writes and documents it: a record's
  /// accessors and `equals`, `hashCode` and `toString`, an enum's `values()` and `valueOf`. javac
  /// marks some of them as written in the source.
  private boolean implicit(TypeElement type, ExecutableElement method) {
    if (elements.getDocComment(method) != null) {
      return false;
    }
    var name = method.getSimpleName().toString();
    if (type.getKind() == ElementKind.ENUM) {
      return name.equals("values") || name.equals("valueOf");
    }
    return type.getKind() == ElementKind.RECORD
        && (Set.of("equals", "hashCode", "toString").contains(name)
            || (method.getParameters().isEmpty()
                && type.getRecordComponents().stream()
                    .anyMatch(component -> component.getSimpleName().contentEquals(name))));
  }

  private static boolean isCanonical(TypeElement type, ExecutableElement constructor) {
    var components = type.getRecordComponents();
    return type.getKind() == ElementKind.RECORD
        && constructor.getParameters().size() == components.size()
        && java.util.stream.IntStream.range(0, components.size())
            .allMatch(
                index ->
                    constructor
                        .getParameters()
                        .get(index)
                        .asType()
                        .toString()
                        .equals(components.get(index).asType().toString()));
  }

  private Map<String, Object> member(Element member, Map<String, Integer> anchors) {
    var name =
        member.getKind() == ElementKind.CONSTRUCTOR
            ? member.getEnclosingElement().getSimpleName().toString()
            : member.getSimpleName().toString();
    int seen = anchors.merge(name, 1, Integer::sum);
    var doc = doc(member);
    var fields = new LinkedHashMap<String, Object>();
    fields.put("kind", member.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
    fields.put("name", name);
    fields.put("anchor", anchor(name) + (seen == 1 ? "" : "-" + seen));
    fields.put(
        "signature",
        switch (member) {
          case ExecutableElement method -> Signatures.of(method);
          case VariableElement field -> Signatures.of(field);
          default -> name;
        });
    fields.put("doc", markdown(doc.text(), "../"));
    fields.put("params", tags(doc.params(), "../"));
    fields.put("returns", markdown(doc.returns(), "../"));
    fields.put("throws", tags(doc.throwing(), "../"));
    return fields;
  }

  private List<Object> tags(Map<String, String> tags, String up) {
    var list = new ArrayList<Object>();
    tags.forEach(
        (name, text) -> {
          var tag = new LinkedHashMap<String, Object>();
          tag.put("name", Signatures.simple(name));
          tag.put("doc", markdown(text, up));
          list.add(tag);
        });
    return list;
  }

  private static String kind(TypeElement type) {
    return switch (type.getKind()) {
      case ANNOTATION_TYPE -> "annotation";
      case RECORD -> "record";
      case ENUM -> "enum";
      case INTERFACE -> "interface";
      default -> "class";
    };
  }

  /// `Mutator.Site` for a nested type, `Mutator` for a top-level one.
  private String nameInPackage(TypeElement type) {
    var qualified = type.getQualifiedName().toString();
    var pkg = elements.getPackageOf(type).getQualifiedName().toString();
    return qualified.substring(pkg.length() + 1);
  }

  /// `Mutator.Site` → `mutator-site`.
  private static String slug(String nameInPackage) {
    return nameInPackage.toLowerCase(Locale.ROOT).replace('.', '-');
  }

  /// `changes` → `changes`, `skippedFor` → `skippedfor`: a member's anchor on its type's page.
  private static String anchor(String member) {
    return member.toLowerCase(Locale.ROOT);
  }

  /// A doc comment, split into its text, first sentence and block tags.
  record Doc(
      String text,
      String summary,
      Map<String, String> params,
      String returns,
      Map<String, String> throwing) {}

  private Doc doc(Element element) {
    var comment = elements.getDocComment(element);
    if (comment == null) {
      return new Doc("", "", Map.of(), "", Map.of());
    }
    var body = new StringBuilder();
    var tags = blockTags(comment, body);
    var params = new LinkedHashMap<String, String>();
    var throwing = new LinkedHashMap<String, String>();
    var returns = "";
    for (String[] tag : tags) {
      switch (tag[0]) {
        case "param" -> params.put(tag[1], tag[2]);
        case "throws" -> throwing.put(tag[1], tag[2]);
        default -> returns = (tag[1] + " " + tag[2]).strip();
      }
    }
    var text = body.toString().strip();
    return new Doc(text, summary(text), params, returns, throwing);
  }

  /// The comment's block tags, each `{kind, name, text}`, with the text before them put in `body`.
  private static List<String[]> blockTags(String comment, StringBuilder body) {
    var tags = new ArrayList<String[]>();
    for (String line : comment.stripIndent().lines().toList()) {
      var tag = TAG.matcher(line.strip());
      if (tag.matches()) {
        tags.add(new String[] {tag.group(1), tag.group(2), tag.group(3)});
      } else if (tags.isEmpty()) {
        body.append(line).append('\n');
      } else {
        var last = tags.getLast();
        last[2] = (last[2] + " " + line.strip()).strip();
      }
    }
    return tags;
  }

  /// The first paragraph's first sentence.
  private static String summary(String text) {
    var paragraph = text.split("\n\\s*\n", 2)[0].replace('\n', ' ');
    var end = SENTENCE_END.matcher(paragraph);
    return end.find() ? paragraph.substring(0, end.start() + 1) : paragraph;
  }

  /// Markdown with each reference a link: to a type's page (`up` is the way from this page to the
  /// pages beside it), a member's anchor on it, or code alone for what isn't documented here.
  private String markdown(String text, String up) {
    var matcher = REFERENCE.matcher(text);
    var out = new StringBuilder();
    while (matcher.find()) {
      var type = matcher.group(1);
      var member = matcher.group(2);
      if (type == null && member == null) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      var shown = (type == null ? "" : type) + (member == null ? "" : member);
      var href = href(type, member, up);
      var code = "`" + shown.replaceFirst("^#", "") + "`";
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(href == null ? code : "[" + code + "](" + href + ")"));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private @org.jspecify.annotations.Nullable String href(
      @org.jspecify.annotations.Nullable String type,
      @org.jspecify.annotations.Nullable String member,
      String up) {
    var anchor = member == null ? "" : "#" + anchor(member.substring(1).replaceFirst("\\(.*", ""));
    if (type == null) {
      return anchor;
    }
    var slug = slugs.get(type);
    return slug == null ? null : up + slug + "/" + anchor;
  }

  /// Signatures as the source would write them, package names left out.
  static final class Signatures {
    private static final String INTERNAL = "io.mutastic.api.Internal";

    private Signatures() {}

    static String simple(String type) {
      return QUALIFIER.matcher(type).replaceAll("").replaceAll(",(?=\\S)", ", ");
    }

    static String of(TypeElement type) {
      var words = new ArrayList<String>();
      modifiers(type).forEach(words::add);
      words.add(kind(type).equals("annotation") ? "@interface" : kind(type));
      words.add(name(type));
      var supertypes =
          type.getInterfaces().stream().map(Object::toString).map(Signatures::simple).toList();
      if (!supertypes.isEmpty() && type.getKind() != ElementKind.ANNOTATION_TYPE) {
        words.add(type.getKind().isInterface() ? "extends" : "implements");
        words.add(String.join(", ", supertypes));
      }
      return String.join(" ", words);
    }

    /// `Change(String label, Optional<String> skipped)`: the name, its type parameters, and a
    /// record's components, one a line when there are more than a few, as the source writes them.
    private static String name(TypeElement type) {
      var name = new StringBuilder(type.getSimpleName());
      if (!type.getTypeParameters().isEmpty()) {
        name.append('<')
            .append(
                String.join(", ", type.getTypeParameters().stream().map(Object::toString).toList()))
            .append('>');
      }
      if (type.getKind() == ElementKind.RECORD) {
        var components =
            type.getRecordComponents().stream()
                .map(
                    component ->
                        simple(component.asType().toString()) + " " + component.getSimpleName())
                .toList();
        name.append(
            components.size() > 3
                ? "(\n    " + String.join(",\n    ", components) + ")"
                : "(" + String.join(", ", components) + ")");
      }
      return name.toString();
    }

    static String of(ExecutableElement method) {
      var words = new ArrayList<String>();
      modifiers(method).forEach(words::add);
      if (!method.getTypeParameters().isEmpty()) {
        words.add(
            "<"
                + String.join(
                    ", ", method.getTypeParameters().stream().map(Object::toString).toList())
                + ">");
      }
      var name =
          method.getKind() == ElementKind.CONSTRUCTOR
              ? method.getEnclosingElement().getSimpleName().toString()
              : simple(method.getReturnType().toString()) + " " + method.getSimpleName();
      var parameters =
          method.getParameters().stream()
              .map(
                  parameter ->
                      simple(parameter.asType().toString()) + " " + parameter.getSimpleName())
              .toList();
      words.add(name + "(" + String.join(", ", parameters) + ")");
      return String.join(" ", words);
    }

    static String of(VariableElement field) {
      if (field.getKind() == ElementKind.ENUM_CONSTANT) {
        return field.getSimpleName().toString();
      }
      var words = new ArrayList<String>();
      modifiers(field).forEach(words::add);
      words.add(simple(field.asType().toString()) + " " + field.getSimpleName());
      return String.join(" ", words);
    }

    /// A nested record, enum or interface is static whatever it says.
    private static boolean impliedStatic(Element element) {
      return element.getKind() == ElementKind.RECORD
          || element.getKind() == ElementKind.ENUM
          || element.getKind().isInterface();
    }

    /// What a reader needs first: `@Internal`, as what it marks isn't for plugins.
    private static List<String> modifiers(Element element) {
      var words = new ArrayList<String>();
      if (element.getAnnotationMirrors().stream()
          .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(INTERNAL))) {
        words.add("@Internal");
      }
      words.addAll(keywords(element));
      return words;
    }

    /// The modifiers a reader needs: `static`, `default`, `abstract` on a class, `sealed`; not
    /// `public`, which every documented element is.
    private static List<String> keywords(Element element) {
      boolean inInterface =
          element.getEnclosingElement() != null
              && element.getEnclosingElement().getKind().isInterface();
      return element.getModifiers().stream()
          .filter(
              modifier ->
                  switch (modifier) {
                    case STATIC -> !impliedStatic(element);
                    case DEFAULT, SEALED, NON_SEALED -> true;
                    case ABSTRACT -> !inInterface && !element.getKind().isInterface();
                    case FINAL -> element.getKind() == ElementKind.FIELD && !inInterface;
                    default -> false;
                  })
          .map(modifier -> modifier.toString())
          .toList();
    }
  }
}
