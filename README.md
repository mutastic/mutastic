# mutastic

<img src=".github/pip.webp" alt="Pip, mutastic's watermelon mascot, smiling" width="150" align="right">

*Find out whether your tests, or your agent's, actually check anything.*

[![CI](https://github.com/mutastic/mutastic/actions/workflows/ci.yml/badge.svg)](https://github.com/mutastic/mutastic/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.mutastic/mutastic-cli?label=Maven%20Central)](https://central.sonatype.com/namespace/io.mutastic)
[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue)](LICENSE)

mutastic is a mutation-testing tool for Java and Rust, and this is its Java edition, for Java and
Kotlin; the Rust edition is [mutastic-rs](https://github.com/mutastic/mutastic-rs). It makes small
changes to your compiled code, such as `>=` to `>`, and runs your tests against each one. A change no test notices is a **missed mutant**:
a place your tests run but don't check. For each one, mutastic shows the inputs your tests used,
suggests the test that would catch it, and gives the command that proves the fix.

**[Website](https://mutastic.io/)** ·
[Documentation](https://docs.mutastic.io/) ·
[Install](https://docs.mutastic.io/getting-started/install/) ·
[Quickstart](https://docs.mutastic.io/getting-started/quickstart/) ·
[Reference](https://docs.mutastic.io/reference/run/)

<a href="https://mutastic.io/launch-video/"><img src="https://media.mutastic.io/launch/poster-wide.webp" alt="A frame from mutastic's launch film. Play the 32-second film on mutastic.io" width="720"></a>

*[Watch the 32-second launch film](https://mutastic.io/launch-video/), with captions and a scene-by-scene description.*

## What it looks like

```text
  ✗ MISSED  no test checks discount() at total == 100
    ┌─ src/main/java/com/example/pricing/Pricing.java:17
    │
 16 │   public int discount(int total) {
 17 │     if (total >= 100) {
    │               ^^ >= changed to >
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
            void discountAtTheTotalEdge() {
              assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0
            }
            check today's value is the one you intend: the test pins what the code does now

    done when  mutastic verify m855c8e3c samples/pricing passes
    reproduce  mutastic run --only=m855c8e3c samples/pricing
    or ignore  mutastic ignore m855c8e3c --reason="<why>" samples/pricing

     Score  90.9%  threshold 80

  PASS  1 missed, above the threshold.
```

Add the suggested test, then prove it:

```sh
mutastic verify m855c8e3c samples/pricing
# mutastic: m855c8e3c is caught by com.example.pricing.PricingTest#discountAtTheTotalEdge
```

## Install

```sh
curl -fsSL https://mutastic.io/install.sh | sh
```

That installs the `mutastic` command for Java, Kotlin and Rust projects alike. Then `mutastic run`
in a Gradle or Maven project: the first time, it fetches this Java edition, which runs on JDK 25. A
Rust project needs no JDK. Or from the build:

- **Gradle:** apply the plugin `id("io.mutastic")`, then `./gradlew mutasticRun`.
- **Maven:** add `io.mutastic:mutastic-maven-plugin` to the build, then `mvn mutastic:run`.

The [Install](https://docs.mutastic.io/getting-started/install/) page has the setup for the current
release. The project mutastic tests can be on JDK 17 or later.

## Status

The Java edition runs Gradle and Maven projects, Kotlin's among them (experimental), with JUnit
Platform, JUnit 4 or TestNG tests and more than one test suite. It has operators in tiers
(`defaults`, `stronger` and `extended`), plugins that add more as rules files or Java, parallel
workers, suggested tests, flaky-test detection, a gate for coding agents, and output for people,
coding agents and tools (JSON Lines, and JSON, HTML and XML reports). What stays the same from one
release to the next, mutant ids, scores, the events format and the plugin API, is on the
[Compatibility](https://docs.mutastic.io/reference/compatibility/) page. Each release's changes
are in the [changelog](CHANGELOG.md).

## Contributing

Issues and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). In short,
`./gradlew build` runs everything CI does; [AGENTS.md](AGENTS.md) has the conventions the code is
held to, [ARCHITECTURE.md](ARCHITECTURE.md) how it's built, and [RELEASING.md](RELEASING.md) how a
release is made. To report a vulnerability, see [SECURITY.md](SECURITY.md). Everyone taking part
agrees to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Licence

[Apache License 2.0](LICENSE).
