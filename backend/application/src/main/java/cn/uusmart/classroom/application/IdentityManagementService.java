package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.nio.charset.StandardCharsets;

public final class IdentityManagementService {
  private final IdentityManagementRepository repository;
  private final AccountRepository accounts;
  private final PasswordHasher passwords;
  private final MediaRepository media;

  public IdentityManagementService(
      IdentityManagementRepository repository,
      AccountRepository accounts,
      PasswordHasher passwords,
      MediaRepository media) {
    this.repository = repository;
    this.accounts = accounts;
    this.passwords = passwords;
    this.media = media;
  }

  public Page<SchoolUser> users(Actor actor, String query, int page, int size) {
    TeachingService.requireAdmin(actor);
    Page.validate(query, page, size);
    return repository.users(actor, query, page, size);
  }

  public SchoolUser user(Actor actor, long id) {
    TeachingService.requireAdmin(actor);
    return repository.user(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public SchoolUser create(
      Actor actor, String username, String password, String displayName, Role role) {
    TeachingService.requireAdmin(actor);
    validateRole(role);
    validatePassword(password);
    return user(
        actor, repository.create(actor, username, passwords.hash(password), displayName, role));
  }

  public SchoolUser update(Actor actor, long id, String displayName, Role role, boolean enabled) {
    TeachingService.requireAdmin(actor);
    validateRole(role);
    user(actor, id);
    repository.update(actor, id, displayName, role, enabled);
    return user(actor, id);
  }

  public void delete(Actor actor, long id) {
    user(actor, id);
    repository.delete(actor, id);
  }

  public SchoolUser profile(Actor actor) {
    TeachingService.requireSchoolUser(actor);
    return repository.user(actor, actor.userId()).orElseThrow(ResourceProblem::missing);
  }

  public SchoolUser updateProfile(
      Actor actor, String displayName, String email, String avatarAssetId) {
    profile(actor);
    if (avatarAssetId != null)
      media
          .asset(actor, avatarAssetId)
          .filter(asset -> asset.ownerId() == actor.userId())
          .orElseThrow(ResourceProblem::missing);
    repository.profile(actor, displayName, email, avatarAssetId);
    return profile(actor);
  }

  public void changePassword(Actor actor, String oldPassword, String newPassword) {
    TeachingService.requireSchoolUser(actor);
    validatePassword(newPassword);
    var account = accounts.findActiveById(actor.userId()).orElseThrow(AuthenticationFailed::new);
    if (!passwords.matches(oldPassword, account.passwordHash())) throw new AuthenticationFailed();
    repository.password(actor, account.passwordHash(), passwords.hash(newPassword));
  }

  private static void validateRole(Role role) {
    if (role == null || role == Role.PLATFORM_OPERATOR)
      throw ResourceProblem.invalid("只能管理学校教师或学校管理员");
  }

  private static void validatePassword(String password) {
    if (password == null
        || password.length() < 12
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ResourceProblem.invalid("新密码至少12字符，UTF-8编码后最多72字节");
  }
}
