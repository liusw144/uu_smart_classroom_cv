package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcLessonRepository implements LessonRepository {
  private static final String SCOPE = "tenant_id=? AND (?=1 OR owner_id=?)";
  private static final String SELECT =
      "SELECT t.*,(t.lifecycle_status<>'PLANNED' OR t.metadata_frozen) AS metadata_locked FROM lesson_task t";
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final JsonMapper json = new JsonMapper();

  public JdbcLessonRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  private static Instant instant(ResultSet row, String field) throws SQLException {
    var value = row.getTimestamp(field);
    return value == null ? null : value.toInstant();
  }

  private static final RowMapper<LessonTask> ROW =
      (r, n) ->
          new LessonTask(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getString("name"),
              r.getLong("course_id"),
              r.getLong("class_id"),
              instant(r, "scheduled_at"),
              instant(r, "scheduled_end_at"),
              r.getString("room_name"),
              r.getString("lifecycle_status"),
              instant(r, "actual_started_at"),
              instant(r, "actual_ended_at"),
              instant(r, "archived_at"),
              r.getLong("row_version"),
              r.getBoolean("metadata_locked"),
              new LessonSnapshot(
                  r.getString("course_name_snapshot"),
                  r.getString("class_name_snapshot"),
                  r.getString("grade_snapshot"),
                  r.getInt("student_count_snapshot"),
                  r.getString("term_snapshot"),
                  r.getString("owner_name_snapshot"),
                  instant(r, "snapshot_captured_at"),
                  r.getString("snapshot_source")));

  private Object[] args(Actor actor, Object... extra) {
    var args =
        new ArrayList<Object>(
            List.of(actor.tenantId(), actor.role() == Role.SCHOOL_ADMIN ? 1 : 0, actor.userId()));
    args.addAll(Arrays.asList(extra));
    return args.toArray();
  }

  public Page<LessonTask> tasks(Actor actor, String query, int page, int size) {
    String where = " WHERE " + SCOPE + " AND name LIKE ?";
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM lesson_task" + where, Long.class, args(actor, "%" + query + "%"));
    return new Page<>(
        jdbc.query(
            SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            ROW,
            args(actor, "%" + query + "%", size, (page - 1) * size)),
        total,
        page,
        size);
  }

  public Optional<LessonTask> task(Actor actor, long id) {
    return jdbc.query(SELECT + " WHERE " + SCOPE + " AND id=?", ROW, args(actor, id)).stream()
        .findFirst();
  }

  private LessonTask locked(Actor actor, long id) {
    return jdbc
        .query(
            "SELECT *,(lifecycle_status<>'PLANNED' OR metadata_frozen) AS metadata_locked FROM lesson_task WHERE "
                + SCOPE
                + " AND id=? FOR UPDATE",
            ROW,
            args(actor, id))
        .stream()
        .findFirst()
        .orElseThrow(ResourceProblem::missing);
  }

  private boolean used(Actor actor, long id) {
    return jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM detection_job WHERE tenant_id=? AND task_id=?) + (SELECT COUNT(*) FROM analysis_report WHERE tenant_id=? AND task_id=?)",
            Long.class,
            actor.tenantId(),
            id,
            actor.tenantId(),
            id)
        > 0;
  }

  private void requireVersion(LessonTask task, Long version) {
    if (version != null && task.version() != version)
      throw ResourceProblem.conflict("课堂已被修改，请刷新后重试");
  }

  private record Context(long ownerId, long classId, LessonSnapshot snapshot) {}

  private Context context(Actor actor, long courseId, String source) {
    // 同一当前读锁住关联资料，避免跨班级或人员变更期间拼出不一致快照。
    return jdbc
        .query(
            "SELECT c.owner_id,c.class_id,c.name AS course_name,c.term,s.name AS class_name,s.grade,s.student_count,COALESCE(NULLIF(a.display_name,''),a.username) AS owner_name FROM course c JOIN school_class s ON s.tenant_id=c.tenant_id AND s.id=c.class_id AND s.owner_id=c.owner_id JOIN school_account a ON a.tenant_id=c.tenant_id AND a.id=c.owner_id WHERE c.tenant_id=? AND c.id=? AND (?=1 OR c.owner_id=?) AND a.enabled=true FOR SHARE",
            (r, n) ->
                new Context(
                    r.getLong("owner_id"),
                    r.getLong("class_id"),
                    new LessonSnapshot(
                        r.getString("course_name"),
                        r.getString("class_name"),
                        r.getString("grade"),
                        r.getInt("student_count"),
                        r.getString("term"),
                        r.getString("owner_name"),
                        Instant.now(),
                        source)),
            actor.tenantId(),
            courseId,
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId())
        .stream()
        .findFirst()
        .orElseThrow(ResourceProblem::missing);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  @Transactional
  public LessonTask save(Actor actor, Long id, LessonTaskEdit edit) {
    edit.validate();
    LessonTask before = id == null ? null : locked(actor, id);
    if (before != null) {
      requireVersion(before, edit.version());
      if (before.metadataLocked() || used(actor, id))
        throw ResourceProblem.conflict("课堂已开始、已有分析记录或已归档，不能改写关键资料");
    }
    Context capture =
        before == null || before.courseId() != edit.courseId()
            ? context(actor, edit.courseId(), before == null ? "CREATED" : "REASSIGNED")
            : new Context(before.ownerId(), before.classId(), before.snapshot());
    var s = capture.snapshot();
    if (before != null && before.ownerId() != capture.ownerId())
      throw ResourceProblem.conflict("课堂不能直接更换负责人，请另建课堂并办理交接");
    var p =
        new MapSqlParameterSource()
            .addValue("tenant", actor.tenantId())
            .addValue("owner", capture.ownerId())
            .addValue("name", edit.name().trim())
            .addValue("course", edit.courseId())
            .addValue("class", capture.classId())
            .addValue("start", timestamp(edit.scheduledAt()))
            .addValue("end", timestamp(edit.scheduledEndAt()))
            .addValue("room", edit.roomName() == null ? "" : edit.roomName().trim())
            .addValue("cn", s.courseName())
            .addValue("sn", s.className())
            .addValue("grade", s.grade())
            .addValue("count", s.studentCount())
            .addValue("term", s.term())
            .addValue("on", s.ownerName())
            .addValue("captured", timestamp(s.capturedAt()))
            .addValue("source", s.source());
    if (before == null) {
      var key = new GeneratedKeyHolder();
      named.update(
          "INSERT INTO lesson_task(tenant_id,owner_id,name,course_id,class_id,scheduled_at,scheduled_end_at,room_name,course_name_snapshot,class_name_snapshot,grade_snapshot,student_count_snapshot,term_snapshot,owner_name_snapshot,snapshot_captured_at,snapshot_source,lifecycle_status) VALUES (:tenant,:owner,:name,:course,:class,:start,:end,:room,:cn,:sn,:grade,:count,:term,:on,:captured,:source,'PLANNED')",
          p,
          key,
          new String[] {"id"});
      id = Objects.requireNonNull(key.getKey()).longValue();
    } else {
      p.addValue("id", id).addValue("version", before.version());
      if (named.update(
              "UPDATE lesson_task SET owner_id=:owner,name=:name,course_id=:course,class_id=:class,scheduled_at=:start,scheduled_end_at=:end,room_name=:room,course_name_snapshot=:cn,class_name_snapshot=:sn,grade_snapshot=:grade,student_count_snapshot=:count,term_snapshot=:term,owner_name_snapshot=:on,snapshot_captured_at=:captured,snapshot_source=:source,row_version=row_version+1 WHERE tenant_id=:tenant AND id=:id AND row_version=:version",
              p)
          != 1) throw ResourceProblem.conflict("课堂已变更，请刷新后重试");
    }
    var after = task(actor, id).orElseThrow(ResourceProblem::missing);
    event(actor, before, after, before == null ? "CREATED" : "UPDATED", "已保存课堂资料；课程重新选择时捕获对应教务快照");
    return after;
  }

  @Transactional
  public LessonTask transition(
      Actor actor, long id, String action, long version, Instant occurredAt) {
    var before = locked(actor, id);
    requireVersion(before, version);
    String target;
    Instant now = Instant.now(),
        start = before.actualStartedAt(),
        end = before.actualEndedAt(),
        archive = null;
    switch (action) {
      case "START" -> {
        if (!Set.of("PLANNED", "LEGACY").contains(before.status()))
          throw ResourceProblem.conflict("只有待开课或待确认的历史课堂可以开始");
        if (before.status().equals("LEGACY") && occurredAt == null)
          throw ResourceProblem.invalid("历史课堂必须显式填写真实开始时间");
        start = occurredAt == null ? now : occurredAt;
        target = "IN_PROGRESS";
      }
      case "COMPLETE" -> {
        if (!before.status().equals("IN_PROGRESS")) throw ResourceProblem.conflict("只有进行中的课堂可以结束");
        end = occurredAt == null ? now : occurredAt;
        target = "COMPLETED";
        if (!end.isAfter(start)) throw ResourceProblem.invalid("实际结束时间必须晚于实际开始时间");
      }
      case "ARCHIVE" -> {
        if (!Set.of("COMPLETED", "LEGACY").contains(before.status()))
          throw ResourceProblem.conflict("只有已结束或待确认的历史课堂可以归档");
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM detection_job WHERE tenant_id=? AND task_id=? AND status IN ('QUEUED','RUNNING')",
                Long.class,
                actor.tenantId(),
                id)
            > 0) throw ResourceProblem.conflict("课堂仍有分析作业，请完成或取消后归档");
        archive = now;
        target = "ARCHIVED";
      }
      default -> throw ResourceProblem.invalid("课堂状态操作无效");
    }
    if ((start != null && start.isAfter(now)) || (end != null && end.isAfter(now)))
      throw ResourceProblem.invalid("实际课堂时间不能晚于服务器当前时间");
    jdbc.update(
        "UPDATE lesson_task SET lifecycle_status=?,actual_started_at=?,actual_ended_at=?,archived_at=?,row_version=row_version+1 WHERE tenant_id=? AND id=?",
        target,
        timestamp(start),
        timestamp(end),
        timestamp(archive),
        actor.tenantId(),
        id);
    var after = task(actor, id).orElseThrow(ResourceProblem::missing);
    event(actor, before, after, action, "课堂状态变更；操作时间与实际课堂时间分别保存");
    return after;
  }

  private void event(Actor actor, LessonTask before, LessonTask after, String action, String note) {
    String actorName =
        jdbc.queryForObject(
            "SELECT COALESCE(NULLIF(display_name,''),username) FROM school_account WHERE tenant_id=? AND id=?",
            String.class,
            actor.tenantId(),
            actor.userId());
    jdbc.update(
        "INSERT INTO lesson_task_event(tenant_id,task_id,owner_id,actor_id,actor_name,action,from_status,to_status,task_version,note,context_json) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        after.id(),
        after.ownerId(),
        actor.userId(),
        actorName,
        action,
        before == null ? null : before.status(),
        after.status(),
        after.version(),
        note,
        json.writeValueAsString(after));
  }

  public Page<LessonTaskEvent> events(Actor actor, long id, int page, int size) {
    task(actor, id).orElseThrow(ResourceProblem::missing);
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM lesson_task_event WHERE tenant_id=? AND task_id=?",
            Long.class,
            actor.tenantId(),
            id);
    return new Page<>(
        jdbc.query(
            "SELECT * FROM lesson_task_event WHERE tenant_id=? AND task_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
            (r, n) ->
                new LessonTaskEvent(
                    r.getLong("id"),
                    r.getLong("actor_id"),
                    r.getString("actor_name"),
                    r.getString("action"),
                    r.getString("from_status"),
                    r.getString("to_status"),
                    r.getLong("task_version"),
                    instant(r, "occurred_at"),
                    r.getString("note")),
            actor.tenantId(),
            id,
            size,
            (page - 1) * size),
        total,
        page,
        size);
  }

  @Transactional
  public void delete(Actor actor, long id) {
    var task = locked(actor, id);
    if (task.metadataLocked() || used(actor, id))
      throw ResourceProblem.conflict("已使用或归档课堂应保留历史，不能直接删除");
    jdbc.update("DELETE FROM lesson_task WHERE tenant_id=? AND id=?", actor.tenantId(), id);
  }
}
