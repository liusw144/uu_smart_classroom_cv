import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class AuditJavaBoundaries {
  static final List<String> results = new ArrayList<>();
  static void run(String name, Runnable action) {
    try { action.run(); results.add(name + "\tpassed"); }
    catch (Throwable error) { results.add(name + "\tfailed\t" + error.getClass().getSimpleName()); }
  }
  static void rejected(LoginService service, String password) {
    try { service.login("audit-school", "audit-user", password); }
    catch (AuthenticationFailed expected) { return; }
    throw new AssertionError("无效密码未被拒绝");
  }
  public static void main(String[] args) {
    var encoder = new BCryptPasswordEncoder(4);
    var account = new Account(7, 1, "audit-user", encoder.encode("audit-password"), Role.TEACHER);
    AccountRepository repository = new AccountRepository() {
      public Optional<Account> findActive(String school, String user) {
        return school.equals("audit-school") && user.equals("audit-user") ? Optional.of(account) : Optional.empty();
      }
      public Optional<Account> findActiveById(long id) { return Optional.of(account); }
    };
    var service = new LoginService(repository, encoder::matches, encoder.encode("dummy-audit"));
    run("J01_valid_password", () -> { if (service.login("audit-school", "audit-user", "audit-password").tenantId() != 1) throw new AssertionError(); });
    run("J02_wrong_password", () -> rejected(service, "wrong-password"));
    run("J03_wrong_school", () -> {
      try { service.login("other-school", "audit-user", "audit-password"); }
      catch (AuthenticationFailed expected) { return; }
      throw new AssertionError();
    });
    run("J04_unicode_password_under_byte_limit", () -> {
      if (!encoder.matches("中文测试密码", encoder.encode("中文测试密码"))) throw new AssertionError();
    });
    run("J05_unicode_password_over_byte_limit_is_auth_rejection", () -> rejected(service, "中".repeat(25)));
    results.forEach(System.out::println);
    if (results.stream().anyMatch(result -> result.contains("\tfailed"))) System.exit(1);
  }
}
