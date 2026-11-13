// Added to the user's build with `-I` to read what the `test` task (or another Test task, named
// by -PmutasticSuite) would do: its JDK, JVM arguments, working directory and classpath, and the
// module's own directories. Written as a properties file to -PmutasticOut. Nothing in the user's
// build changes.
// The source directories of a set: Java's, and Kotlin's when its plugin is there.
fun sourceDirs(set: SourceSet): Set<File> =
    set.java.srcDirs + ((set.extensions.findByName("kotlin") as? SourceDirectorySet)?.srcDirs ?: emptySet())

// A property of the Kotlin plugin's model, read by name: this script can't see the plugin's types.
fun Any.kotlinProperty(name: String): Any? = withGroovyBuilder { getProperty(name) }

// A Kotlin Multiplatform module's JVM targets (`jvm()`, `jvm("desktop")`): none for a module
// without the plugin. A target's code is in its compilations, not in source sets, and the `java`
// plugin is never applied.
fun jvmTargets(project: Project): List<Any> {
    if (!project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
        return emptyList()
    }
    val targets = project.extensions.getByName("kotlin").kotlinProperty("targets") as Iterable<*>
    return targets.filterNotNull().filter { (it.kotlinProperty("platformType") as Enum<*>).name == "jvm" }
}

fun compilation(target: Any, name: String): Any =
    (target.kotlinProperty("compilations") as NamedDomainObjectCollection<*>).getByName(name)!!

// Whether mutastic can run on the module: it has Java's plugin, or a JVM target.
fun isJvmModule(project: Project): Boolean =
    project.plugins.hasPlugin("java") || jvmTargets(project).isNotEmpty()

// The configurations the module's tests run with: one per JVM target for Kotlin Multiplatform.
fun testRuntimes(project: Project): List<String> =
    if (project.plugins.hasPlugin("java")) {
        listOf("testRuntimeClasspath")
    } else {
        jvmTargets(project).map { compilation(it, "test").kotlinProperty("runtimeDependencyConfigurationName") as String }
    }

// One side of a suite, the code or its tests: their class and resource directories, sources, and
// the task that builds them.
class Side(val classesDirs: FileCollection, val resourcesDir: File?, val sources: Set<File>, val built: String)

// What a suite runs: the Test task, the code it tests and the tests, the configuration its classpath
// resolves, and the jar other modules see of the code, if there is one.
class Suite(val task: TaskProvider<Test>, val main: Side, val test: Side, val runtime: String, val jar: String)

// The `java` plugin's suite: the code is the main source set, the tests the set whose classes the
// Test task runs, `integrationTest` say.
fun javaSuite(project: Project, task: TaskProvider<Test>): Suite {
    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    val main = sourceSets["main"]
    val test = sourceSets.find { set ->
        set.name != "main" && set.output.classesDirs.files.any { it in task.get().testClassesDirs.files }
    } ?: sourceSets["test"]
    fun side(set: SourceSet) = Side(set.output.classesDirs, set.output.resourcesDir, sourceDirs(set), set.classesTaskName)
    return Suite(task, side(main), side(test), test.runtimeClasspathConfigurationName, "jar")
}

// A JVM target's suite: its `main` and `test` compilations, and its Test task, `desktopTest` for
// `jvm("desktop")`.
fun kotlinSuite(target: Any, task: TaskProvider<Test>): Suite {
    fun side(compilation: Any): Side {
        val output = compilation.kotlinProperty("output")!!
        val sources = (compilation.kotlinProperty("allKotlinSourceSets") as Iterable<*>)
            .flatMap { (it!!.kotlinProperty("kotlin") as SourceDirectorySet).srcDirs }
            .toSet()
        return Side(
            output.kotlinProperty("classesDirs") as FileCollection,
            output.kotlinProperty("resourcesDir") as File?,
            sources,
            compilation.kotlinProperty("compileAllTaskName") as String,
        )
    }
    val test = compilation(target, "test")
    val name = target.kotlinProperty("name") as String
    return Suite(
        task,
        side(compilation(target, "main")),
        side(test),
        test.kotlinProperty("runtimeDependencyConfigurationName") as String,
        name + "Jar",
    )
}

// The suite -PmutasticSuite names: a Test task, `test` unless it names another. A Kotlin
// Multiplatform module has no `test` task: its one JVM target's stands in for it.
fun suiteOf(project: Project): Suite {
    val asked = (project.findProperty("mutasticSuite") as String?) ?: "test"
    val tests = project.tasks.withType<Test>()
    val targets = jvmTargets(project)
    val name = if (asked == "test" && asked !in tests.names && targets.size == 1) {
        (targets.single().kotlinProperty("name") as String) + "Test"
    } else {
        asked
    }
    if (name !in tests.names) {
        val suites = tests.names.sorted().joinToString(", ")
        val where = if (project.path == ":") "the root project" else project.path
        throw GradleException("mutastic: no test suite $name in $where; its Test tasks are $suites")
    }
    val task = project.tasks.named<Test>(name)
    if (project.plugins.hasPlugin("java")) {
        return javaSuite(project, task)
    }
    val target = targets.find { (it.kotlinProperty("name") as String) + "Test" == name }
        ?: throw GradleException("mutastic: the test suite $name in ${project.path} isn't a JVM target's")
    return kotlinSuite(target, task)
}

// When the module asked for has no Java code: which modules do, to suggest one.
rootProject {
    tasks.register("mutasticModules") {
        val out = File(project.findProperty("mutasticOut") as String)
        val java = project.provider {
            rootProject.allprojects.filter { isJvmModule(it) }.map { it.path }
        }
        doLast {
            out.parentFile.mkdirs()
            out.writeText(java.get().joinToString("\n"))
        }
    }
}

allprojects {
    // Building for mutastic must not reformat the user's sources (some builds make compileJava
    // depend on spotlessApply). Disabled where it exists; builds without Spotless are unaffected.
    tasks.matching { it.name == "spotlessApply" }.configureEach { enabled = false }

    // A module with Java's plugin, or Kotlin Multiplatform's, gets mutastic's tasks once.
    pluginManager.withPlugin("java") { mutasticTasks(project) }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { mutasticTasks(project) }
}

fun mutasticTasks(project: Project) = with(project) {
    if ("mutasticSetup" in tasks.names) {
        return@with
    }
    // The modules whose tests can reach this one: its project on their resolved test classpath,
    // directly or through others. Written one module path per line.
    tasks.register("mutasticDependents") {
        val out = File(project.findProperty("mutasticOut") as String)
        val target = project.path
        val dependents = project.provider {
            rootProject.allprojects
                .filter { it.path != target && isJvmModule(it) }
                .filter { other ->
                    testRuntimes(other).mapNotNull { other.configurations.findByName(it) }.any { runtime ->
                        runtime.incoming.resolutionResult.allComponents
                            .any { (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath == target }
                    }
                }
                .map { it.path }
                .sorted()
        }
        doLast {
            out.parentFile.mkdirs()
            out.writeText(dependents.get().joinToString("\n"))
        }
    }

    tasks.register("mutasticSetup") {
        // Gradle runs a task in a project whose name only starts as the one asked for does, :core
        // for :cor. mutastic tests only the module named, and says when the build hasn't got it.
        val asked = project.findProperty("mutasticProject") as String?
        if (asked != null && asked != project.path) {
            throw GradleException("mutastic: no project $asked in this build")
        }
        val suite = suiteOf(project)
        val suiteTask = suite.task
        val main = suite.main
        val test = suite.test
        // Everything on the suite's classpath is built, as the Test task would build it: another
        // module's jar, on a fresh checkout, as well as this module's classes. The classpath
        // itself, not a provider mapped from the task, which would run the tests first.
        dependsOn(main.built, test.built, suiteTask.get().classpath)
        // Quarkus's plugin hands its Test task a pre-built application model as the task runs,
        // so it isn't among the task's JVM arguments. Without it, every @QuarkusTest asks
        // Gradle for the model again.
        val quarkusModel = tasks.findByName("quarkusGenerateTestAppModel")
        if (quarkusModel != null) {
            dependsOn(quarkusModel)
        }
        val out = File(project.findProperty("mutasticOut") as String)
        val separator = File.pathSeparator
        doLast {
            val task = suiteTask.get()
            val model = quarkusModel?.outputs?.files?.files?.firstOrNull { it.name.endsWith("test-model.dat") }
            val quarkus = listOfNotNull(model?.let { "-Dquarkus-internal-test.serialized-app-model.path=" + it.absolutePath })
            val own = (main.classesDirs.files + test.classesDirs.files +
                listOfNotNull(main.resourcesDir, test.resourcesDir)).map { it.absolutePath }.toSet()
            val setup = java.util.Properties()
            setup["java"] = task.javaLauncher.get().executablePath.asFile.absolutePath
            setup["jvmArgs"] = (task.allJvmArgs + quarkus).joinToString("\n")
            setup["workingDirectory"] = task.workingDir.absolutePath
            setup["classes"] = main.classesDirs.files.joinToString(separator)
            setup["resources"] = listOfNotNull(main.resourcesDir).joinToString(separator)
            setup["testClasses"] = test.classesDirs.files.joinToString(separator)
            setup["testResources"] = listOfNotNull(test.resourcesDir).joinToString(separator)
            setup["sources"] = main.sources.joinToString(separator)
            setup["testSources"] = test.sources.joinToString(separator)
            // The module's own directories are left out: the worker loads those itself, so
            // mutants can replace them. So are the build's other modules, which it loads with
            // them: one may look this module's classes up by name (Class.forName), as it can
            // on the test task's one classpath.
            val modules = configurations.getByName(suite.runtime).incoming
                .artifactView { componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier } }
                .files.files.map { it.absolutePath }.toSet()
            val classpath = task.classpath.files.map { it.absolutePath }.filterNot { it in own }
            setup["dependencies"] = classpath.filterNot { it in modules }.joinToString(separator)
            setup["alongside"] = classpath.filter { it in modules }.joinToString(separator)
            // What another module sees of this one: taken off its classpath when its tests run
            // over this module's mutants, so the mutated classes are the ones loaded.
            val jar = (tasks.findByName(suite.jar) as? org.gradle.jvm.tasks.Jar)?.archiveFile?.get()?.asFile
            setup["outputs"] = (main.classesDirs.files + listOfNotNull(main.resourcesDir, jar))
                .joinToString(separator) { it.absolutePath }
            // Which tests the task runs, so mutastic runs the same ones.
            setup["testIncludes"] = task.includes.joinToString("\n")
            setup["testExcludes"] = task.excludes.joinToString("\n")
            val platform = task.options as? org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
            // TestNG's groups are tags on the platform, as its engine runs them.
            val testng = task.options as? org.gradle.api.tasks.testing.testng.TestNGOptions
            setup["includeTags"] = (platform?.includeTags ?: testng?.includeGroups).orEmpty().joinToString("\n")
            setup["excludeTags"] = (platform?.excludeTags ?: testng?.excludeGroups).orEmpty().joinToString("\n")
            // The task's environment starts as Gradle's own: only what it sets beyond that.
            val inherited = System.getenv()
            setup["environment"] = task.environment
                .filter { (name, value) -> inherited[name] != value.toString() }
                .map { (name, value) -> "$name=$value" }
                .joinToString("\n")
            out.parentFile.mkdirs()
            out.outputStream().use { setup.store(it, "mutastic setup for ${project.path}") }
        }
    }
}
