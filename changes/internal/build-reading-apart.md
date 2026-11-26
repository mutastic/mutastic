- **Build reading is held apart from mutants.** `ArchitectureTest` holds the build tools, the
  plugins and the Maven extension to the api's build types (`BuildTool`, `ProjectSetup`, `Project`
  and those they use), and those types to nothing else of the api's, so proptastic can read a build
  the way mutastic does without the mutation model.
