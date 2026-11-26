- **A test class a renamed copy would break runs by its own name.** Each run defines a test class
  in a package production code also uses as a renamed copy (`BinderTest__m2`), so its statics and
  what a library keeps of it start clean. That broke tests that reach a test class by its own name,
  which failed before any mutant though they pass in the build: a test class in another package
  that refers to one (jcommander), or code that names one (kotlinpoet). Where its class files show
  that, from another package's reference, an annotation's class value, a string or a name read at
  run time, the class now runs by its own name; every other stays a copy. A catch by a test that
  can see its statics runs again in a fresh loader before it counts, as since 1.4.0.
