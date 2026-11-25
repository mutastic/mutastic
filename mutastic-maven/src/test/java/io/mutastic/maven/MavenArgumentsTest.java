package io.mutastic.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuildArguments.Refused;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenArgumentsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "-o",
        "--offline",
        "-Pci",
        "-P ci",
        "-Dkey=value",
        "-D key=value",
        "-DskipTests",
        "-Dtest.port=1",
        "-s settings.xml",
        "-ntp",
        "--no-transfer-progress",
        "-fae",
        "-ff",
        "-llr",
        "--fail-at-end",
        "-T 2",
        "-U"
      })
  void anArgumentForTheBuildIsTaken(String arguments) {
    assertThat(MavenArguments.refused(List.of(arguments.split(" ")))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "-Dtest=Foo",
        "-Dtest",
        "-D test=Foo",
        "--define test=Foo",
        "--define=it.test=Foo",
        "-Dmaven.main.skip",
        "-Dmaven.test.skip=true",
        "-Dmaven.resources.skip",
        "-Dmutastic.out=/x",
        "-f other",
        "--file=other",
        "-file other",
        "--fil other",
        "-pl core",
        "-plcore",
        "-fother/pom.xml",
        "-lbuild.log",
        "-am",
        "-N",
        "-q",
        "-fn",
        "--fail-never",
        "--fail-n",
        "-X",
        "clean",
        "-o clean"
      })
  void oneThatFightsMutasticIsRefused(String arguments) {
    assertThat(MavenArguments.refused(List.of(arguments.split(" ")))).isPresent();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SECRET",
        "--unknown SECRET",
        "-o SECRET",
        "-plSECRET",
        "-fSECRET",
        "-lSECRET",
        "-rfSECRET",
        "--file=SECRET",
        "-Dtest=SECRET",
        "-D test=SECRET"
      })
  void aRefusalNeverSaysAWordThatCouldBeAValue(String arguments) {
    assertThat(MavenArguments.refused(List.of(arguments.split(" "))))
        .hasValueSatisfying(refused -> assertThat(refused.toString()).doesNotContain("SECRET"));
  }

  @Test
  void aRefusalNamesTheArgumentWithoutItsValue() {
    assertThat(MavenArguments.refused(List.of("-Dmutastic.key=secret")))
        .contains(new Refused(0, "-Dmutastic.key", "it's a setting of mutastic's own"));
  }
}
