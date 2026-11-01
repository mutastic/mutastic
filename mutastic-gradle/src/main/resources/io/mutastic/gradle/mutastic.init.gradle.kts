// Added to the user's build with `-I` to read what the `test` task would do: its JDK, JVM
// arguments, working directory and classpath, and the module's own directories. Written as a
// properties file to -PmutasticOut. Nothing in the user's build changes.
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
        tasks.register("mutasticSetup") {
            val sourceSets = project.extensions.getByType<SourceSetContainer>()
            val main = sourceSets["main"]
            val test = sourceSets["test"]
            val testTask = tasks.named<Test>("test")
            dependsOn(main.classesTaskName, test.classesTaskName)
            val out = File(project.findProperty("mutasticOut") as String)
            val separator = File.pathSeparator
            doLast {
                val task = testTask.get()
                val own = (main.output.classesDirs.files + test.output.classesDirs.files +
                    listOfNotNull(main.output.resourcesDir, test.output.resourcesDir)).map { it.absolutePath }.toSet()
                val setup = java.util.Properties()
                setup["java"] = task.javaLauncher.get().executablePath.asFile.absolutePath
                setup["jvmArgs"] = task.allJvmArgs.joinToString("\n")
                setup["workingDirectory"] = task.workingDir.absolutePath
                setup["classes"] = main.output.classesDirs.files.joinToString(separator)
                setup["resources"] = listOfNotNull(main.output.resourcesDir).joinToString(separator)
                setup["testClasses"] = test.output.classesDirs.files.joinToString(separator)
                setup["testResources"] = listOfNotNull(test.output.resourcesDir).joinToString(separator)
                setup["sources"] = main.java.srcDirs.joinToString(separator)
                setup["testSources"] = test.java.srcDirs.joinToString(separator)
                // The module's own directories are left out: the worker loads those itself, so
                // mutants can replace them.
                setup["dependencies"] = task.classpath.files.map { it.absolutePath }.filterNot { it in own }.joinToString(separator)
                // Which tests the task runs, so mutastic runs the same ones.
                setup["testIncludes"] = task.includes.joinToString("\n")
                setup["testExcludes"] = task.excludes.joinToString("\n")
                val platform = task.options as? org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
                setup["includeTags"] = platform?.includeTags.orEmpty().joinToString("\n")
                setup["excludeTags"] = platform?.excludeTags.orEmpty().joinToString("\n")
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
