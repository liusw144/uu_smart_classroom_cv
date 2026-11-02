package cn.uusmart.classroom.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@org.springframework.core.annotation.Order(0)
@ConditionalOnProperty(name = "classroom.bootstrap.enabled", havingValue = "true")
public class DevelopmentBootstrap implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final BCryptPasswordEncoder passwords;
  private final Environment environment;

  @Value("${classroom.bootstrap.password:}")
  private String password;

  public DevelopmentBootstrap(
      JdbcTemplate jdbc, BCryptPasswordEncoder passwords, Environment environment) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.environment = environment;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!environment.acceptsProfiles(Profiles.of("dev"))
        || environment.acceptsProfiles(Profiles.of("prod"))
        || password.length() < 12
        || password.length() > 64) {
      throw new IllegalStateException("初始化仅限 dev 环境，密码长度必须为 12 至 64 字符");
    }
    jdbc.update("INSERT IGNORE INTO tenant(code, name) VALUES ('local-school', '本地开发学校')");
    Long tenantId =
        jdbc.queryForObject("SELECT id FROM tenant WHERE code = 'local-school'", Long.class);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM school_account WHERE tenant_id = ? AND username = 'admin'",
            Integer.class,
            tenantId);
    if (count != null && count == 0) {
      jdbc.update(
          "INSERT INTO school_account(tenant_id, username, password_hash, role) VALUES (?, 'admin', ?, 'SCHOOL_ADMIN')",
          tenantId,
          passwords.encode(password));
    }
  }
}
