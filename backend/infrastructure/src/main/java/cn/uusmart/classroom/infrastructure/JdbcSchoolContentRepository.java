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
public class JdbcSchoolContentRepository implements SchoolContentRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<Notice> NOTICE =
      (r, n) ->
          new Notice(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getString("title"),
              r.getString("content"),
              r.getBoolean("published"));
  private static final RowMapper<CatalogEntry> ENTRY =
      (r, n) ->
          new CatalogEntry(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getString("name"),
              r.getString("code"),
              r.getString("description"),
              r.getBoolean("enabled"));

  public JdbcSchoolContentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private String table(Catalog kind) {
    return switch (kind) {
      case MODEL -> "model_catalog";
      case CATEGORY -> "behavior_category";
    };
  }

  private int admin(Actor actor) {
    return actor.role() == Role.SCHOOL_ADMIN ? 1 : 0;
  }

  private long insert(String sql, Object... values) {
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
          return s;
        },
        keys);
    return Objects.requireNonNull(keys.getKey()).longValue();
  }

  public Page<Notice> notices(Actor actor, String q, int page, int size) {
    String where = " WHERE tenant_id=? AND (?=1 OR published=true) AND title LIKE ?";
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM school_notice" + where,
            Long.class,
            actor.tenantId(),
            admin(actor),
            "%" + q + "%");
    return new Page<>(
        jdbc.query(
            "SELECT * FROM school_notice" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            NOTICE,
            actor.tenantId(),
            admin(actor),
            "%" + q + "%",
            size,
            (page - 1) * size),
        count,
        page,
        size);
  }

  public Optional<Notice> notice(Actor actor, long id) {
    return jdbc
        .query(
            "SELECT * FROM school_notice WHERE tenant_id=? AND id=? AND (?=1 OR published=true)",
            NOTICE,
            actor.tenantId(),
            id,
            admin(actor))
        .stream()
        .findFirst();
  }

  @Transactional
  public long saveNotice(Actor actor, Long id, String title, String content, boolean published) {
    if (id == null)
      return insert(
          "INSERT INTO school_notice(tenant_id,title,content,published) VALUES (?,?,?,?)",
          actor.tenantId(),
          title,
          content,
          published);
    if (jdbc.update(
            "UPDATE school_notice SET title=?,content=?,published=? WHERE tenant_id=? AND id=?",
            title,
            content,
            published,
            actor.tenantId(),
            id)
        == 0) throw ResourceProblem.missing();
    return id;
  }

  public void deleteNotice(Actor actor, long id) {
    if (jdbc.update("DELETE FROM school_notice WHERE tenant_id=? AND id=?", actor.tenantId(), id)
        == 0) throw ResourceProblem.missing();
  }

  public Page<CatalogEntry> catalog(Actor actor, Catalog kind, String q, int page, int size) {
    String where = " WHERE tenant_id=? AND (?=1 OR enabled=true) AND (name LIKE ? OR code LIKE ?)";
    String like = "%" + q + "%";
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table(kind) + where,
            Long.class,
            actor.tenantId(),
            admin(actor),
            like,
            like);
    return new Page<>(
        jdbc.query(
            "SELECT * FROM " + table(kind) + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            ENTRY,
            actor.tenantId(),
            admin(actor),
            like,
            like,
            size,
            (page - 1) * size),
        count,
        page,
        size);
  }

  public Optional<CatalogEntry> entry(Actor actor, Catalog kind, long id) {
    return jdbc
        .query(
            "SELECT * FROM "
                + table(kind)
                + " WHERE tenant_id=? AND id=? AND (?=1 OR enabled=true)",
            ENTRY,
            actor.tenantId(),
            id,
            admin(actor))
        .stream()
        .findFirst();
  }

  @Transactional
  public long saveEntry(
      Actor actor,
      Catalog kind,
      Long id,
      String name,
      String code,
      String description,
      boolean enabled) {
    if (id == null)
      return insert(
          "INSERT INTO "
              + table(kind)
              + "(tenant_id,name,code,description,enabled) VALUES (?,?,?,?,?)",
          actor.tenantId(),
          name,
          code,
          description,
          enabled);
    if (jdbc.update(
            "UPDATE "
                + table(kind)
                + " SET name=?,code=?,description=?,enabled=? WHERE tenant_id=? AND id=?",
            name,
            code,
            description,
            enabled,
            actor.tenantId(),
            id)
        == 0) throw ResourceProblem.missing();
    return id;
  }

  public void deleteEntry(Actor actor, Catalog kind, long id) {
    if (jdbc.update(
            "DELETE FROM " + table(kind) + " WHERE tenant_id=? AND id=?", actor.tenantId(), id)
        == 0) throw ResourceProblem.missing();
  }
}
