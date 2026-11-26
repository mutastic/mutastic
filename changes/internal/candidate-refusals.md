- **The candidate run knows the samples built to be refused.** Seven samples exist to show a
  refusal: a missing JDK toolchain in Gradle and Maven, tests on JDK 8, a Gradle too old for the
  build's JDK, JUnit 3 alone, TestNG before 6.14.3, and classes without line numbers. The candidate
  run took each refusal for a broken run. It now passes each one only when it's refused with its
  own error code, and fails it on any other.
