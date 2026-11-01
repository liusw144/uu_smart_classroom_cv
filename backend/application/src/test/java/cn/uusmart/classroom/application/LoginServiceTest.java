package cn.uusmart.classroom.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.uusmart.classroom.domain.Account;
import cn.uusmart.classroom.domain.Role;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginServiceTest {
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
          "dummy");

  @Test
  void schoolAndPasswordSelectServerOwnedTenant() {
    assertEquals(9, service.login("school-a", "teacher", "correct").tenantId());
  }

  @Test
  void wrongPasswordIsRejected() {
    assertThrows(AuthenticationFailed.class, () -> service.login("school-a", "teacher", "wrong"));
  }

  @Test
  void wrongSchoolCannotSelectAccount() {
    assertThrows(AuthenticationFailed.class, () -> service.login("school-b", "teacher", "correct"));
  }
}
