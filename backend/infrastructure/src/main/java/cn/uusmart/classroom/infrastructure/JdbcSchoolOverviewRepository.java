package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSchoolOverviewRepository implements SchoolOverviewRepository {
  private final JdbcTemplate jdbc;

  public JdbcSchoolOverviewRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Long> dashboard(Actor actor) {
    var counts = new LinkedHashMap<String, Long>();
    for (var entry :
        Map.of("class_count", "school_class", "course_count", "course", "task_count", "lesson_task")
            .entrySet())
      counts.put(
          entry.getKey(),
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM "
                  + entry.getValue()
                  + " WHERE tenant_id=? AND (?=1 OR owner_id=?)",
              Long.class,
              actor.tenantId(),
              actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
              actor.userId()));
    counts.put(
        "detection_count",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM detection_job WHERE tenant_id=? AND (?=1 OR owner_id=?)",
            Long.class,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId()));
    counts.put(
        "failed_detection_count",
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM detection_job WHERE tenant_id=? AND (?=1 OR owner_id=?) AND status='FAILED'",
            Long.class,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId()));
    return counts;
  }

  public Page<OperationLog> logs(Actor actor, String q, int page, int size) {
    String where = " WHERE tenant_id=? AND (path LIKE ? OR username LIKE ?)";
    String like = "%" + q + "%";
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operation_log" + where, Long.class, actor.tenantId(), like, like);
    var rows =
        jdbc.query(
            "SELECT * FROM operation_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            (r, n) ->
                new OperationLog(
                    r.getLong("id"),
                    r.getLong("tenant_id"),
                    r.getLong("actor_id"),
                    r.getString("username"),
                    r.getString("method"),
                    r.getString("path"),
                    r.getInt("status"),
                    r.getString("request_id"),
                    r.getTimestamp("created_at").toInstant()),
            actor.tenantId(),
            like,
            like,
            size,
            (page - 1) * size);
    return new Page<>(rows, count, page, size);
  }

  public void append(Actor actor, String method, String path, int status, String requestId) {
    jdbc.update(
        "INSERT INTO operation_log(tenant_id,actor_id,username,method,path,status,request_id) SELECT tenant_id,id,username,?,?,?,? FROM school_account WHERE tenant_id=? AND id=?",
        method,
        path.length() > 255 ? path.substring(0, 255) : path,
        status,
        requestId,
        actor.tenantId(),
        actor.userId());
  }
}
