package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcIdentityManagementRepository implements IdentityManagementRepository {
  private final JdbcTemplate jdbc;
  private static final String FIELDS =
      "id,tenant_id,username,display_name,email,role,enabled,avatar_asset_id";
  private static final RowMapper<SchoolUser> USER =
      (r, n) ->
          new SchoolUser(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getString("username"),
              r.getString("display_name"),
              r.getString("email"),
              Role.valueOf(r.getString("role")),
              r.getBoolean("enabled"),
              r.getString("avatar_asset_id"));

  public JdbcIdentityManagementRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Page<SchoolUser> users(Actor actor, String query, int page, int size) {
    String where =
        " WHERE tenant_id=? AND role <> 'PLATFORM_OPERATOR' AND (username LIKE ? OR display_name LIKE ?)";
    String like = "%" + query + "%";
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM school_account" + where,
            Long.class,
            actor.tenantId(),
            like,
            like);
    return new Page<>(
        jdbc.query(
            "SELECT "
                + FIELDS
                + " FROM school_account"
                + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            USER,
            actor.tenantId(),
            like,
            like,
            size,
            (page - 1) * size),
        total,
        page,
        size);
  }

  public Optional<SchoolUser> user(Actor actor, long id) {
    return jdbc
        .query(
            "SELECT "
                + FIELDS
                + " FROM school_account WHERE tenant_id=? AND id=? AND role <> 'PLATFORM_OPERATOR'",
            USER,
            actor.tenantId(),
            id)
        .stream()
        .findFirst();
  }

  @Transactional
  public long create(Actor actor, String username, String hash, String displayName, Role role) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var s =
              c.prepareStatement(
                  "INSERT INTO school_account(tenant_id,username,password_hash,display_name,role) VALUES (?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          s.setLong(1, actor.tenantId());
          s.setString(2, username);
          s.setString(3, hash);
          s.setString(4, displayName);
          s.setString(5, role.name());
          return s;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  private void protectAdministrator(Actor actor, long id, Role role, boolean enabled) {
    jdbc.queryForObject(
        "SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, actor.tenantId());
    var previous = user(actor, id).orElseThrow(ResourceProblem::missing);
    if (id == actor.userId() && (!enabled || role != Role.SCHOOL_ADMIN))
      throw ResourceProblem.conflict("不能停用、删除或降级当前管理员，请使用其他管理员处理");
    if (previous.role() == Role.SCHOOL_ADMIN
        && previous.enabled()
        && (!enabled || role != Role.SCHOOL_ADMIN)) {
      int count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM school_account WHERE tenant_id=? AND role='SCHOOL_ADMIN' AND enabled=true",
              Integer.class,
              actor.tenantId());
      if (count <= 1) throw ResourceProblem.conflict("至少保留一名启用的学校管理员");
    }
  }

  @Transactional
  public void update(Actor actor, long id, String displayName, Role role, boolean enabled) {
    protectAdministrator(actor, id, role, enabled);
    if (jdbc.update(
            "UPDATE school_account SET display_name=?,role=?,enabled=? WHERE tenant_id=? AND id=? AND role <> 'PLATFORM_OPERATOR'",
            displayName,
            role.name(),
            enabled,
            actor.tenantId(),
            id)
        == 0) throw ResourceProblem.missing();
  }

  @Transactional
  public void delete(Actor actor, long id) {
    protectAdministrator(actor, id, Role.TEACHER, false);
    if (jdbc.update(
            "DELETE FROM school_account WHERE tenant_id=? AND id=? AND role <> 'PLATFORM_OPERATOR'",
            actor.tenantId(),
            id)
        == 0) throw ResourceProblem.missing();
  }

  public void profile(Actor actor, String name, String email, String avatarAssetId) {
    if (jdbc.update(
            "UPDATE school_account SET display_name=?,email=?,avatar_asset_id=? WHERE tenant_id=? AND id=? AND enabled=true",
            name,
            email,
            avatarAssetId,
            actor.tenantId(),
            actor.userId())
        == 0) throw ResourceProblem.missing();
  }

  public void password(Actor actor, String previousHash, String newHash) {
    if (jdbc.update(
            "UPDATE school_account SET password_hash=? WHERE tenant_id=? AND id=? AND password_hash=? AND enabled=true",
            newHash,
            actor.tenantId(),
            actor.userId(),
            previousHash)
        == 0) throw ResourceProblem.conflict("账号状态或密码已变更，请重新登录后重试");
  }
}
