package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTeachingRepository implements TeachingRepository {
  private final JdbcTemplate jdbc;
  private static final String SCOPE = "tenant_id=? AND (?=1 OR owner_id=?)";
  private static final RowMapper<SchoolClass> CLASSES =
      (r, n) ->
          new SchoolClass(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getString("name"),
              r.getString("grade"),
              r.getInt("student_count"));
  private static final RowMapper<Course> COURSES =
      (r, n) ->
          new Course(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getString("name"),
              r.getLong("class_id"),
              r.getString("term"));
  private static final RowMapper<LessonTask> TASKS =
      (r, n) ->
          new LessonTask(
              r.getLong("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getString("name"),
              r.getLong("course_id"),
              r.getLong("class_id"),
              r.getTimestamp("scheduled_at") == null
                  ? null
                  : r.getTimestamp("scheduled_at").toInstant());

  public JdbcTeachingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private Object[] scope(Actor actor) {
    return new Object[] {
      actor.tenantId(), actor.role() == Role.SCHOOL_ADMIN ? 1 : 0, actor.userId()
    };
  }

  private Object[] args(Actor actor, Object... extra) {
    var values = new ArrayList<>(Arrays.asList(scope(actor)));
    values.addAll(Arrays.asList(extra));
    return values.toArray();
  }

  private <T> Page<T> list(
      Actor actor, String table, String query, int page, int size, RowMapper<T> mapper) {
    var where = " WHERE " + SCOPE + " AND name LIKE ?";
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + where, Long.class, args(actor, "%" + query + "%"));
    var items =
        jdbc.query(
            "SELECT * FROM " + table + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            mapper,
            args(actor, "%" + query + "%", size, (page - 1) * size));
    return new Page<>(items, count, page, size);
  }

  private <T> Optional<T> find(Actor actor, String table, long id, RowMapper<T> mapper) {
    return jdbc
        .query("SELECT * FROM " + table + " WHERE " + SCOPE + " AND id=?", mapper, args(actor, id))
        .stream()
        .findFirst();
  }

  private long insert(String sql, Object... values) {
    var key = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
          return statement;
        },
        key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }

  private void delete(Actor actor, String table, long id) {
    if (jdbc.update("DELETE FROM " + table + " WHERE " + SCOPE + " AND id=?", args(actor, id)) == 0)
      throw ResourceProblem.missing();
  }

  public Page<SchoolClass> classes(Actor actor, String query, int page, int size) {
    return list(actor, "school_class", query, page, size, CLASSES);
  }

  public Optional<SchoolClass> schoolClass(Actor actor, long id) {
    return find(actor, "school_class", id, CLASSES);
  }

  @Transactional
  public long saveClass(Actor actor, Long id, long ownerId, String name, String grade, int count) {
    if (id == null)
      return insert(
          "INSERT INTO school_class(tenant_id,owner_id,name,grade,student_count) VALUES (?,?,?,?,?)",
          actor.tenantId(),
          ownerId,
          name,
          grade,
          count);
    if (jdbc.update(
            "UPDATE school_class SET name=?,grade=?,student_count=? WHERE tenant_id=? AND (?=1 OR owner_id=?) AND id=?",
            name,
            grade,
            count,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId(),
            id)
        == 0) throw ResourceProblem.missing();
    return id;
  }

  public void deleteClass(Actor actor, long id) {
    delete(actor, "school_class", id);
  }

  public Page<Course> courses(Actor actor, String query, int page, int size) {
    return list(actor, "course", query, page, size, COURSES);
  }

  public Optional<Course> course(Actor actor, long id) {
    return find(actor, "course", id, COURSES);
  }

  @Transactional
  public long saveCourse(
      Actor actor, Long id, long ownerId, String name, long classId, String term) {
    if (id == null)
      return insert(
          "INSERT INTO course(tenant_id,owner_id,name,class_id,term) VALUES (?,?,?,?,?)",
          actor.tenantId(),
          ownerId,
          name,
          classId,
          term);
    if (jdbc.update(
            "UPDATE course SET owner_id=?,name=?,class_id=?,term=? WHERE tenant_id=? AND (?=1 OR owner_id=?) AND id=?",
            ownerId,
            name,
            classId,
            term,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId(),
            id)
        == 0) throw ResourceProblem.missing();
    return id;
  }

  public void deleteCourse(Actor actor, long id) {
    delete(actor, "course", id);
  }

  public Page<LessonTask> tasks(Actor actor, String query, int page, int size) {
    return list(actor, "lesson_task_view", query, page, size, TASKS);
  }

  public Optional<LessonTask> task(Actor actor, long id) {
    return find(actor, "lesson_task_view", id, TASKS);
  }

  @Transactional
  public long saveTask(
      Actor actor, Long id, long ownerId, String name, long courseId, Instant scheduledAt) {
    var timestamp = scheduledAt == null ? null : Timestamp.from(scheduledAt);
    if (id == null)
      return insert(
          "INSERT INTO lesson_task(tenant_id,owner_id,name,course_id,scheduled_at) VALUES (?,?,?,?,?)",
          actor.tenantId(),
          ownerId,
          name,
          courseId,
          timestamp);
    if (jdbc.update(
            "UPDATE lesson_task SET owner_id=?,name=?,course_id=?,scheduled_at=? WHERE tenant_id=? AND (?=1 OR owner_id=?) AND id=?",
            ownerId,
            name,
            courseId,
            timestamp,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId(),
            id)
        == 0) throw ResourceProblem.missing();
    return id;
  }

  public void deleteTask(Actor actor, long id) {
    delete(actor, "lesson_task", id);
  }
}
