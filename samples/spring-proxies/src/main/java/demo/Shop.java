package demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/// The shop's beans. `Prices` is `@Transactional` and has no interface, so Spring proxies it with a
/// CGLIB subclass of it.
@Configuration
@EnableTransactionManagement
public class Shop {
  @Bean
  public Prices prices() {
    return new Prices();
  }

  /// Transactions that do nothing: what the proxy calls around each method.
  @Bean
  public PlatformTransactionManager transactions() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }
}
