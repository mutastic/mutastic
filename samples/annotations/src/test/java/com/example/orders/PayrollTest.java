package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

@SpringJUnitConfig(PayrollTest.Config.class)
class PayrollTest {
  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean
    Payroll payroll() {
      return new Payroll();
    }

    @Bean
    Transfers transfers() {
      return new Transfers();
    }

    @Bean
    static MethodValidationPostProcessor validation() {
      return new MethodValidationPostProcessor();
    }
  }

  @Autowired Payroll payroll;
  @Autowired Transfers transfers;

  @Test
  @WithMockUser(roles = "USER")
  void onlyAnAdminIsPaid() {
    assertThatThrownBy(payroll::pay).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void anAdminAudits() {
    assertThat(payroll.audit()).isEqualTo("audited");
  }

  @Test
  void aTransferNeedsSomewhereToGo() {
    assertThatThrownBy(() -> transfers.send(null, "x"))
        .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
  }
}
