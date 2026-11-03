package cn.uusmart.classroom.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.uusmart.classroom.domain.Account;
import cn.uusmart.classroom.domain.Role;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginServiceTest {
  private final LoginBudget budget =
      (school, username, source) ->
          new LoginBudget.Permit() {
            public void identify(long accountId) {}

            public void close() {}
          };
  private final Account account = new Account(3, 9, "teacher", "encoded", Role.TEACHER);
  private final AccountRepository repository =
      new AccountRepository() {
        public Optional<Account> findActive(String school, String username) {
          return school.equals("school-a") && username.equals("teacher")
              ? Optional.of(account)
              : Optional.empty();
        }

        public Optional<Account> findActiveById(long id) {
          return Optional.empty();
        }
      };
  private final LoginService service =
      new LoginService(
          repository,
          (raw, encoded) -> raw.equals("correct") && encoded.equals("encoded"),
          "dummy",
          budget);

  @Test
  void rejectedAdmissionNeverRunsPasswordVerification() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var denied =
        new LoginService(
            repository,
            (raw, encoded) -> {
              calls.incrementAndGet();
              return false;
            },
            "dummy",
            (school, user, source) -> {
              throw new RequestThrottled(1);
            });
    assertThrows(
        RequestThrottled.class, () -> denied.login("school-a", "teacher", "wrong", "127.0.0.1"));
    assertEquals(0, calls.get());
  }

  @Test
  void failedPasswordCheckReleasesPermitAndUsesDummyForUnknownAccount() {
    var closed = new java.util.concurrent.atomic.AtomicInteger();
    var guarded =
        new LoginService(
            repository,
            (raw, encoded) -> {
              assertEquals("dummy", encoded);
              return false;
            },
            "dummy",
            (school, user, source) ->
                new LoginBudget.Permit() {
                  public void identify(long id) {
                    fail("未知账号不应有真实账号编号");
                  }

                  public void close() {
                    closed.incrementAndGet();
                  }
                });
    assertThrows(
        AuthenticationFailed.class,
        () -> guarded.login("school-a", "unknown", "wrong", "127.0.0.1"));
    assertEquals(1, closed.get());
  }

  @Test
  void schoolAndPasswordSelectServerOwnedTenant() {
    assertEquals(9, service.login("school-a", "teacher", "correct", "127.0.0.1").tenantId());
  }

  @Test
  void wrongPasswordIsRejected() {
    assertThrows(
        AuthenticationFailed.class,
        () -> service.login("school-a", "teacher", "wrong", "127.0.0.1"));
  }

  @Test
  void wrongSchoolCannotSelectAccount() {
    assertThrows(
        AuthenticationFailed.class,
        () -> service.login("school-b", "teacher", "correct", "127.0.0.1"));
  }
}
