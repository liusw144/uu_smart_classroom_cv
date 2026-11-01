package cn.uusmart.classroom.api.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.uusmart.classroom.application.AccountRepository;
import cn.uusmart.classroom.domain.Role;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class PermissionAdapter implements StpInterface {
  private final AccountRepository accounts;

  public PermissionAdapter(AccountRepository accounts) {
    this.accounts = accounts;
  }

  public List<String> getPermissionList(Object loginId, String loginType) {
    return accounts
        .findActiveById(Long.parseLong(loginId.toString()))
        .map(account -> permissions(account.role()))
        .orElse(List.of());
  }

  public List<String> getRoleList(Object loginId, String loginType) {
    return accounts
        .findActiveById(Long.parseLong(loginId.toString()))
        .map(account -> List.of(account.role().name()))
        .orElse(List.of());
  }

  public static List<String> permissions(Role role) {
    return switch (role) {
      case TEACHER ->
          List.of("session:read", "lesson:read:own", "lesson:write:own", "detection:run:own");
      case SCHOOL_ADMIN -> List.of("session:read", "lesson:manage:school", "user:manage:school");
      case PLATFORM_OPERATOR -> List.of("session:read", "tenant:operate");
    };
  }
}
