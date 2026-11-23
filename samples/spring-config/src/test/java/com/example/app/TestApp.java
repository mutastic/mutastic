package com.example.app;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/// The tests' app: the whole package, with a clock that reads ten in the morning. Its scan of the
/// package finds this class too.
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = OpeningHours.class)
public class TestApp {
  @Bean
  Clock clock() {
    return Clock.fixed(Instant.parse("2026-01-05T10:00:00Z"), ZoneOffset.UTC);
  }
}
