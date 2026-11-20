- **The JDK's `@Generated` counts, though javac keeps it for the source alone.**
  `javax.annotation.processing.Generated`, `javax.annotation.Generated` and
  `jakarta.annotation.Generated` never reach the class file, so the code annotation processors
  (Dagger, MapStruct, AutoValue) and generators (OpenAPI Generator, jOOQ) write was mutated and
  scored as yours. mutastic now reads a Java class's source file too: a top-level type with
  `@Generated` on it is skipped as ``generated: @Generated on `Order` in the source``, with every
  type nested in it. Comments and strings that only mention it don't count. The Gradle plugin adds
  where annotation processors write (`build/generated/sources/annotationProcessor`) to the
  module's sources, as Maven already does. Projects with such code have fewer mutants, and their
  score moves. See [Annotations](https://docs.mutastic.io/guides/configuration/#annotations).
