# mutastic

*Find out whether your tests, or your agent's, actually check anything.*

[![CI](https://github.com/mutastic/mutastic/actions/workflows/ci.yml/badge.svg)](https://github.com/mutastic/mutastic/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.mutastic/mutastic-cli?label=Maven%20Central)](https://central.sonatype.com/namespace/io.mutastic)
[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue)](LICENSE)

mutastic is a mutation-testing tool for Java. It makes small changes to your compiled code, such as
`>=` to `>`, and runs your tests against each one. A change no test notices is a **missed mutant**:
a place your tests run but don't check. For each one, mutastic shows the inputs your tests used,
suggests the test that would catch it, and gives the command that proves the fix.

**[Documentation](https://docs.mutastic.io/)** ·
[Install](https://docs.mutastic.io/getting-started/install/) ·
[Quickstart](https://docs.mutastic.io/getting-started/quickstart/) ·
[Reference](https://docs.mutastic.io/reference/run/)

## What it looks like

```text
  ✗ MISSED  no test checks discount() at total == 100
    ┌─ src/main/java/com/example/pricing/Pricing.java:17
    │
 16 │   public int discount(int total) {
 17 │     if (total >= 100) {
    │               ^^ changed to >
 18 │       return total / 10;
    │
    = mutant  - if (total >= 100) {
              + if (total > 100) {
    = note: every test calls discount() away from the boundary
            inputs seen: 50, 250 (none is 100)
            covered by PricingTest#bigOrdersGetTenPercentOff, PricingTest#smallOrdersPayFullPrice (all 2 covering tests still pass)
    = help: test discount() with total = 100 and assert the result

            // in PricingTest
            @Test
            void discountWithTotal100() {
              assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0
            }
            check today's value is the one you intend: the test pins what the code does now

    done when  mutastic verify m855c8e3c samples/pricing passes
    reproduce  mutastic run --only=m855c8e3c samples/pricing
    or ignore  mutastic ignore m855c8e3c --reason="<why>" samples/pricing

     Score  85.7%  threshold 80

  PASS  1 missed, above the threshold.
```

Add the suggested test, then prove it:

```sh
mutastic verify m855c8e3c samples/pricing
# mutastic: m855c8e3c is caught by com.example.pricing.PricingTest#discountWithTotal100
```

## Install

- **Gradle:** apply the plugin `id("io.mutastic")`, then `./gradlew mutasticRun`.
- **Maven:** add `io.mutastic:mutastic-maven-plugin` to the build, then `mvn mutastic:run`.
- **Command line:** download the release's zip, then `mutastic run` in a Gradle or Maven project.

The [Install](https://docs.mutastic.io/getting-started/install/) page has the setup for the current
release. mutastic runs on JDK 25; the project it tests can be on JDK 17 or later.

## Status

Early. Five operators (boundaries, negated conditions, arithmetic, returned defaults, removed void
calls), Gradle and Maven projects with JUnit Platform tests, parallel workers, suggested tests,
flaky-test detection, and output for people, coding agents and tools (JSON Lines, and JSON, HTML
and XML reports). Expect it to change before 1.0. Each release's changes are in its
[release notes](https://github.com/mutastic/mutastic/releases).

## Contributing

Issues and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). In short,
`./gradlew build` runs everything CI does; [AGENTS.md](AGENTS.md) has the conventions the code is
held to, [ARCHITECTURE.md](ARCHITECTURE.md) how it's built, and [RELEASING.md](RELEASING.md) how a
release is made. To report a vulnerability, see [SECURITY.md](SECURITY.md). Everyone taking part
agrees to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Licence

[Apache License 2.0](LICENSE).
