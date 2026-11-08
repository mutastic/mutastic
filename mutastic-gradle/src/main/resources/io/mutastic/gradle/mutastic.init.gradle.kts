// Added to the user's build with `-I` to read what the `test` task (or another Test task, named
// by -PmutasticSuite) would do: its JDK, JVM arguments, working directory and classpath, and the
// module's own directories. Written as a properties file to -PmutasticOut. Nothing in the user's
// build changes.
// The source directories of a set: Java's, and Kotlin's when its plugin is there.
fun sourceDirs(set: SourceSet): Set<File> =
    set.java.srcDirs + ((set.extensions.findByName("kotlin") as? SourceDirectorySet)?.srcDirs ?: emptySet())

// When the module asked for has no Java code: which modules do, to suggest one.
rootProject {
    tasks.register("mutasticModules") {
        val out = File(project.findProperty("mutasticOut") as String)
        val java = project.provider {
            rootProject.allprojects.filter { it.plugins.hasPlugin("java") }.map { it.path }
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

    plugins.withType<JavaPlugin> {
        // The Java modules whose tests can reach this one: its project on their resolved test
        // classpath, directly or through others. Written one module path per line.
        tasks.register("mutasticDependents") {
            val out = File(project.findProperty("mutasticOut") as String)
            val target = project.path
            val dependents = project.provider {
                rootProject.allprojects
                    .filter { it.path != target && it.plugins.hasPlugin("java") }
                    .filter { other ->
                        other.configurations.findByName("testRuntimeClasspath")
                            ?.incoming?.resolutionResult?.allComponents.orEmpty()
                            .any { (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath == target }
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
            val sourceSets = project.extensions.getByType<SourceSetContainer>()
            val main = sourceSets["main"]
            // The suite: a Test task, `test` unless -PmutasticSuite names another, and the source
            // set whose classes it runs, `integrationTest` say.
            val suite = (project.findProperty("mutasticSuite") as String?) ?: "test"
            val testTask = tasks.withType<Test>().named { it == suite }
            if (testTask.isEmpty()) {
                val suites = tasks.withType<Test>().names.sorted().joinToString(", ")
                val where = if (project.path == ":") "the root project" else project.path
                throw GradleException("mutastic: no test suite $suite in $where; its Test tasks are $suites")
            }
            val suiteTask = tasks.named<Test>(suite)
            val test = sourceSets.find { set ->
                set.name != "main" && set.output.classesDirs.files.any { it in suiteTask.get().testClassesDirs.files }
            } ?: sourceSets["test"]
            // Everything on the suite's classpath is built, as the Test task would build it: another
            // module's jar, on a fresh checkout, as well as this module's classes. The classpath
            // itself, not a provider mapped from the task, which would run the tests first.
            dependsOn(main.classesTaskName, test.classesTaskName, suiteTask.get().classpath)
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
                val own = (main.output.classesDirs.files + test.output.classesDirs.files +
                    listOfNotNull(main.output.resourcesDir, test.output.resourcesDir)).map { it.absolutePath }.toSet()
                val setup = java.util.Properties()
                setup["java"] = task.javaLauncher.get().executablePath.asFile.absolutePath
                setup["jvmArgs"] = (task.allJvmArgs + quarkus).joinToString("\n")
                setup["workingDirectory"] = task.workingDir.absolutePath
                setup["classes"] = main.output.classesDirs.files.joinToString(separator)
                setup["resources"] = listOfNotNull(main.output.resourcesDir).joinToString(separator)
                setup["testClasses"] = test.output.classesDirs.files.joinToString(separator)
                setup["testResources"] = listOfNotNull(test.output.resourcesDir).joinToString(separator)
                setup["sources"] = sourceDirs(main).joinToString(separator)
                setup["testSources"] = sourceDirs(test).joinToString(separator)
                // The module's own directories are left out: the worker loads those itself, so
                // mutants can replace them.
                setup["dependencies"] = task.classpath.files.map { it.absolutePath }.filterNot { it in own }.joinToString(separator)
                // What another module sees of this one: taken off its classpath when its tests run
                // over this module's mutants, so the mutated classes are the ones loaded.
                val jar = (tasks.findByName("jar") as? org.gradle.jvm.tasks.Jar)?.archiveFile?.get()?.asFile
                setup["outputs"] = (main.output.classesDirs.files + listOfNotNull(main.output.resourcesDir, jar))
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
}
