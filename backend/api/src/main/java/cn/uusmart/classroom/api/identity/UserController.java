package cn.uusmart.classroom.api.identity;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserController {
  private final IdentityManagementService service;
  private final CurrentActor current;

  public UserController(IdentityManagementService service, CurrentActor current) {
    this.service = service;
    this.current = current;
  }

  public record CreateUser(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(max = 128) String displayName,
      @NotNull Role role) {}

  public record UpdateUser(
      @NotBlank @Size(max = 128) String displayName,
      @NotNull Role role,
      @NotNull Boolean enabled) {}

  public record ProfileInput(
      @NotBlank @Size(max = 128) String displayName,
      @NotNull @Email @Size(max = 254) String email,
      @Pattern(regexp = "[a-f0-9-]{36}") String avatarAssetId) {}

  public record PasswordInput(
      @NotBlank @Size(max = 72) String oldPassword,
      @NotBlank @Size(min = 12, max = 72) String newPassword) {}

  @GetMapping("/users")
  @SaCheckPermission("user:manage:school")
  public ApiResponse<Page<SchoolUser>> users(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.users(current.get(), q, page, size));
  }

  @GetMapping("/users/{id}")
  @SaCheckPermission("user:manage:school")
  public ApiResponse<SchoolUser> user(@PathVariable long id) {
    return new ApiResponse<>(service.user(current.get(), id));
  }

  @PostMapping("/users")
  @SaCheckPermission("user:manage:school")
  public ApiResponse<SchoolUser> create(@Valid @RequestBody CreateUser input) {
    return new ApiResponse<>(
        service.create(
            current.get(),
            input.username(),
            input.password(),
            input.displayName().trim(),
            input.role()));
  }

  @PutMapping("/users/{id}")
  @SaCheckPermission("user:manage:school")
  public ApiResponse<SchoolUser> update(
      @PathVariable long id, @Valid @RequestBody UpdateUser input) {
    var value =
        service.update(
            current.get(), id, input.displayName().trim(), input.role(), input.enabled());
    StpUtil.kickout(id);
    return new ApiResponse<>(value);
  }

  @DeleteMapping("/users/{id}")
  @SaCheckPermission("user:manage:school")
  public ApiResponse<Boolean> delete(@PathVariable long id) {
    service.delete(current.get(), id);
    StpUtil.kickout(id);
    return new ApiResponse<>(true);
  }

  @GetMapping("/profile")
  @SaCheckPermission("session:read")
  public ApiResponse<SchoolUser> profile() {
    return new ApiResponse<>(service.profile(current.get()));
  }

  @PutMapping("/profile")
  @SaCheckPermission("session:read")
  public ApiResponse<SchoolUser> profile(@Valid @RequestBody ProfileInput input) {
    return new ApiResponse<>(
        service.updateProfile(
            current.get(),
            input.displayName().trim(),
            input.email().trim(),
            input.avatarAssetId()));
  }

  @PostMapping("/profile/password")
  @SaCheckPermission("session:read")
  public ApiResponse<Boolean> password(@Valid @RequestBody PasswordInput input) {
    var actor = current.get();
    service.changePassword(actor, input.oldPassword(), input.newPassword());
    StpUtil.kickout(actor.userId());
    return new ApiResponse<>(true);
  }
}
