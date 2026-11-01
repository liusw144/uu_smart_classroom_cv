package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import cn.uusmart.classroom.application.AccountRepository;
import cn.uusmart.classroom.domain.Account;
import cn.uusmart.classroom.infrastructure.AccountMapper;
import cn.uusmart.classroom.infrastructure.MybatisAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.persistence.autoconfigure.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RepositoryProxyTest {
  @Test
  void repositoryCanStartWithRealExceptionTranslationConfiguration() {
    assertDoesNotThrow(
        () -> {
          try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                AccountMapper.class,
                () ->
                    new AccountMapper() {
                      public Account findActive(String schoolCode, String username) {
                        return null;
                      }

                      public Account findActiveById(long id) {
                        return null;
                      }
                    });
            context.register(
                PersistenceExceptionTranslationAutoConfiguration.class,
                MybatisAccountRepository.class);
            context.refresh();
            assertNotNull(context.getBean(AccountRepository.class));
          }
        });
  }
}
