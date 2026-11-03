# Architecture

How mutastic is built, for people working on it. To use it, see the [documentation](https://docs.mutastic.io/); for the conventions the code is held to, [AGENTS.md](AGENTS.md).

## How a run works

1. **Setup.** mutastic runs your build with its own code loaded, which compiles the code and tests and records what the tests would run with: their JDK, JVM arguments, working directory and classpath. For Gradle that's an init script reading the `test` task; for Maven, an extension (`-Dmaven.ext.class.path`) reading Surefire's settings after `mvn test-compile`. Your build files are not changed.
2. **Mutants.** The engine reads your class files with the JDK's ClassFile API (`java.lang.classfile`, final in JDK 24) and finds every mutation site, for each operator the run uses: mutastic's own (`BuiltIn`), its stream rules, and any a plugin adds (`Mutator`, as Java or as a rules file). A mutant's id is made from its method, site and change, so a new operator never changes the ids of the others.
3. **Coverage.** A test worker starts on your project's JDK and runs every test once, with each line instrumented. This records which tests reach which lines, and doubles as a check that the tests pass.
4. **Mutants, side by side.** For each mutant, the engine sends the changed class to a worker. The worker runs only the tests that reach that line, fastest first, and stops at the first failure. Classes load once per worker and each mutant is swapped in with class redefinition, so nothing is restarted between mutants. Redefinition never reruns a static initialiser, so a line the coverage pass saw run only while its class was loading (a table parsed into a static field) is the exception: that mutant runs in a fresh class loader, against every test that reaches its class. A worker that takes too long is killed and replaced: a mutant can turn a loop into one that never ends. Tests in a framework that loads the app in its own class loader (`@QuarkusTest`) run in the worker's framework mode: the framework loads the project's classes from the classpath, the worker's agent hands it the instrumented or mutated bytes as it defines them, and redefines its copies for each mutant after that.

   Several workers run at once (`--workers`, by default half the cores, at most 4), each its own JVM, as Gradle's parallel test forks are. Results are reported in source order whatever order they finish in. Workers side by side can get in each other's way: tests that use a fixed port collide, and tests that share a lock, a database or a slow start (a Spring context) run slower than they did alone. So neither a catch caused by a port in use nor a timeout is trusted: that mutant runs again on its own once the others are done.

The same run serves three entry points. The `mutastic` command reads the build from outside, through the build tool. The Gradle and Maven plugins read it from inside the build, write the same setup, and run the `mutastic` command on it in a JDK 25 process of their own, since the build itself may run on 17.

## Modules

| Module | JDK | What it does |
|---|---|---|
| `mutastic-annotations` | 17 | `@DoNotMutate` and `@DoMutate`, for your code: nothing else of mutastic's |
| `mutastic-api` | 25 | The result model every other module shares: a project, its mutants and their results |
| `mutastic-engine` | 25 | Finds, applies and verifies mutants; coverage; runs the worker |
| `mutastic-worker` | 17 | Runs your tests on your JDK; a Java agent for swapping classes |
| `mutastic-report` | 25 | The formats: for people, for agents, JSON Lines, and the report files |
| `mutastic-gradle` | 25 | Reads a Gradle build's setup, with an init script; a `BuildTool` |
| `mutastic-maven` | 25 | Reads a Maven build's setup, with the extension; a `BuildTool` |
| `mutastic-maven-extension` | 17 | Runs inside your Maven build and writes what Surefire would run |
| `mutastic-cli` | 25 | The `mutastic` command: finds the build tool, runs the engine, picks the format |
| `mutastic-gradle-plugin` | 17 | The Gradle plugin: writes a module's setup from inside the build, then runs the `mutastic` command on it |
| `mutastic-maven-plugin` | 17 | The Maven plugin: writes a module's setup from inside the build, then runs the `mutastic` command on it |

## Boundaries

`ArchitectureTest` (in `mutastic-cli`'s tests, which see every module) enforces the module boundaries, rather than leaving them to be described:

- `mutastic-api` depends on nothing of mutastic's.
- The engine knows nothing of output, and the report module knows only the result model.
- Build tools know only the api, and nothing names a build tool: the CLI finds them with `ServiceLoader`.
- The worker and the Maven extension stand alone: they run inside the project's own JVM and build.
- Nothing depends on `mutastic-annotations`, which is matched by simple name.
- No regex group is read by number.

## One declaration for each option

Every option the CLI takes is declared once, in `mutastic-cli`'s `Options`. The parser reads it, `--help` is written from it, the build plugins' `mutastic.` properties are read back through it, and so is the docs' command reference (`docs/src/data/commands.json`, from `./gradlew :mutastic-cli:exportDocsModel`). The build fails when the committed reference and the code disagree, and when any command the docs show doesn't parse.
