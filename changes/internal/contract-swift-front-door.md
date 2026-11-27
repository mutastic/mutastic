- **The contract has the front door's Swift routing.** The Rust edition's native front door takes
  `--edition=swift`, reads `Package.swift`, an `.xcodeproj` or an `.xcworkspace` as a Swift project,
  and refuses it as `swift-edition` until a Swift edition is released, with
  `MUTASTIC_SWIFT_ENGINE` naming one built from source (N5, N11 and N31). A directory with the
  build files of more than one edition, `Cargo.toml` beside `Package.swift`, is refused as
  `both-editions` until `--edition` names one, as `Cargo.toml` beside `pom.xml` is. The Swift
  edition has no binding yet. No Java behaviour changes.
