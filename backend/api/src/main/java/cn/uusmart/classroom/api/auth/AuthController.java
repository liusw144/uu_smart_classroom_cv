package cn.uusmart.classroom.api.auth;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.AccountRepository;
import cn.uusmart.classroom.application.AuthenticationFailed;
import cn.uusmart.classroom.application.LoginService;
import cn.uusmart.classroom.domain.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final LoginService login;
  private final AccountRepository accounts;

  public AuthController(LoginService login, AccountRepository accounts) {
    this.login = login;
    this.accounts = accounts;
  }

  public record LoginRequest(
      @NotBlank @Size(max = 64) String schoolCode,
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(max = 72) String password) {}

  public record UserView(
      long id, long tenantId, String username, String role, List<String> permissions) {}

  public record LoginView(String accessToken, long expiresIn, UserView user) {}

  @PostMapping("/login")
  public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request) {
    Account account = login.login(request.schoolCode(), request.username(), request.password());
    StpUtil.login(account.id());
    StpUtil.getSession().set("tenant_id", account.tenantId());
    return new ApiResponse<>(
        new LoginView(StpUtil.getTokenValue(), StpUtil.getTokenTimeout(), view(account)));
  }

  @GetMapping("/me")
  @SaCheckPermission("session:read")
  public ApiResponse<UserView> me() {
    Account account =
        accounts.findActiveById(StpUtil.getLoginIdAsLong()).orElseThrow(AuthenticationFailed::new);
    Object sessionTenant = StpUtil.getSession().get("tenant_id");
    if (sessionTenant == null || Long.parseLong(sessionTenant.toString()) != account.tenantId()) {
      StpUtil.logout();
      throw new AuthenticationFailed();
    }
    return new ApiResponse<>(view(account));
  }

  @PostMapping("/logout")
  public ApiResponse<Boolean> logout() {
    StpUtil.logout();
    return new ApiResponse<>(true);
  }

  private UserView view(Account account) {
    return new UserView(
        account.id(),
        account.tenantId(),
        account.username(),
        account.role().name(),
        PermissionAdapter.permissions(account.role()));
  }
}
