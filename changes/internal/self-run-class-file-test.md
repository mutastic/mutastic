- **The nightly self-run passes its baseline again.** `ClassFileVersionTest` read its own class
  file and expected its own name, but mutastic runs a test in a production package as a renamed
  copy (`ClassFileVersionTest__m3`), whose name literal is renamed while the file it reads is the
  build's. Every self-run shard stopped on it from 14 Nov. The test now reads a production class,
  `ClassFileVersion`, so it checks the same parse and version refusal under either name.
