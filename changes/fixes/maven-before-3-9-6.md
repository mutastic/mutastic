- **A build on Maven before 3.9.6 is read, not refused as having no Java code.** Maven 3.6.3 to
  3.9.5 passed over mutastic's extension without a word, since it was built for a newer JDK than
  the Sisu in that Maven reads, so every module read as having no Java code. It's now built for
  JDK 11, and runs on Maven 3.6.3 or later, the minimum the
  [Install](https://docs.mutastic.io/getting-started/install/#which-gradle-and-maven-run) page now
  gives.
