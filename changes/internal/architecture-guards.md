- **`ArchitectureTest` holds processes, the environment, printing and its own rules.** Only
  today's classes start a process or read the environment, only the worker sets a thread's context
  loader, and nothing outside the CLI, the output kit and the worker (whose stderr is how it
  reports to the engine) writes to `System.out` or `System.err`. Every `io.mutastic` package is
  one a rule names, and every rule field is one ArchUnit runs and says `dependOn`, not `access`.
