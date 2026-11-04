package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMediaRepository implements MediaRepository {
  public Page<MediaAsset> assets(Actor actor, String query, int page, int size) {
    String where = " WHERE " + SCOPE + " AND storage_state='READY' AND filename LIKE ?";
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM media_asset" + where, Long.class, args(actor, "%" + query + "%"));
    return new Page<>(
        jdbc.query(
            "SELECT * FROM media_asset"
                + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            ASSET,
            args(actor, "%" + query + "%", size, (page - 1) * size)),
        total,
        page,
        size);
  }

  public long taskJobCount(Actor actor, long taskId, String status) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM detection_job WHERE " + SCOPE + " AND task_id=? AND status=?",
        Long.class,
        args(actor, taskId, status));
  }

  private final JdbcTemplate jdbc;
  private static final String SCOPE = "tenant_id=? AND (?=1 OR owner_id=?)";
  private static final RowMapper<MediaAsset> ASSET =
      (r, n) ->
          new MediaAsset(
              r.getString("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getString("filename"),
              r.getString("content_type"),
              r.getLong("size_bytes"),
              r.getString("sha256"));
  private static final RowMapper<DetectionJob> JOB =
      (r, n) ->
          new DetectionJob(
              r.getString("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getLong("task_id"),
              r.getString("task_name"),
              r.getString("asset_id"),
              r.getLong("model_id"),
              r.getString("model_version"),
              r.getString("status"),
              r.getString("error_code"),
              r.getTimestamp("created_at").toInstant());
  private static final RowMapper<TeachingReport> REPORT =
      (r, n) ->
          new TeachingReport(
              r.getString("id"),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getLong("task_id"),
              r.getString("title"),
              r.getString("content"),
              r.getString("source"),
              r.getTimestamp("created_at").toInstant());

  public JdbcMediaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private Object[] args(Actor actor, Object... more) {
    var values =
        new ArrayList<Object>(
            List.of(actor.tenantId(), actor.role() == Role.SCHOOL_ADMIN ? 1 : 0, actor.userId()));
    values.addAll(Arrays.asList(more));
    return values.toArray();
  }

  public Optional<MediaAsset> asset(Actor actor, String id) {
    return jdbc
        .query(
            "SELECT * FROM media_asset WHERE " + SCOPE + " AND storage_state='READY' AND id=?",
            ASSET,
            args(actor, id))
        .stream()
        .findFirst();
  }

  @Transactional
  public void createJob(DetectionJob j, long taskVersion) {
    LessonWriteGuard.lockWritable(jdbc, j.tenantId(), j.taskId(), j.ownerId(), taskVersion);
    jdbc.update(
        "INSERT INTO detection_job(id,tenant_id,owner_id,task_id,asset_id,model_id,model_version,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
        j.id(),
        j.tenantId(),
        j.ownerId(),
        j.taskId(),
        j.assetId(),
        j.modelId(),
        j.modelVersion(),
        j.status(),
        Timestamp.from(j.createdAt()));
  }

  public void failJob(Actor actor, String id, String code) {
    if (jdbc.update(
            "UPDATE detection_job SET status='FAILED',error_code=? WHERE tenant_id=? AND (?=1 OR owner_id=?) AND id=? AND status='RUNNING'",
            code,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId(),
            id)
        == 0) throw ResourceProblem.conflict("作业状态已变更");
  }

  public Page<DetectionJob> jobs(Actor actor, String query, String status, int page, int size) {
    String where = " WHERE " + SCOPE + " AND task_name LIKE ? AND (?='' OR status=?)";
    long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM detection_job_view" + where,
            Long.class,
            args(actor, "%" + query + "%", status, status));
    return new Page<>(
        jdbc.query(
            "SELECT * FROM detection_job_view"
                + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            JOB,
            args(actor, "%" + query + "%", status, status, size, (page - 1) * size)),
        total,
        page,
        size);
  }

  public Optional<DetectionJob> job(Actor actor, String id) {
    return jdbc
        .query(
            "SELECT * FROM detection_job_view WHERE " + SCOPE + " AND id=?", JOB, args(actor, id))
        .stream()
        .findFirst();
  }

  @Transactional
  public void deleteJob(Actor actor, String id) {
    lockDeletion(actor, "detection_job", id);
    if (jdbc.update("DELETE FROM detection_job WHERE " + SCOPE + " AND id=?", args(actor, id)) == 0)
      throw ResourceProblem.missing();
  }

  @Transactional
  public void createReport(TeachingReport r, long taskVersion) {
    LessonWriteGuard.lockWritable(jdbc, r.tenantId(), r.taskId(), r.ownerId(), taskVersion);
    jdbc.update(
        "INSERT INTO analysis_report(id,tenant_id,owner_id,task_id,title,content,source,created_at) VALUES (?,?,?,?,?,?,?,?)",
        r.id(),
        r.tenantId(),
        r.ownerId(),
        r.taskId(),
        r.title(),
        r.content(),
        r.source(),
        Timestamp.from(r.createdAt()));
  }

  public Page<TeachingReport> reports(Actor actor, String query, int page, int size) {
    String where = " WHERE " + SCOPE + " AND title LIKE ?";
    long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM analysis_report" + where,
            Long.class,
            args(actor, "%" + query + "%"));
    return new Page<>(
        jdbc.query(
            "SELECT * FROM analysis_report"
                + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            REPORT,
            args(actor, "%" + query + "%", size, (page - 1) * size)),
        count,
        page,
        size);
  }

  public Optional<TeachingReport> report(Actor actor, String id) {
    return jdbc
        .query(
            "SELECT * FROM analysis_report WHERE " + SCOPE + " AND id=?", REPORT, args(actor, id))
        .stream()
        .findFirst();
  }

  @Transactional
  public void deleteReport(Actor actor, String id) {
    lockDeletion(actor, "analysis_report", id);
    if (jdbc.update("DELETE FROM analysis_report WHERE " + SCOPE + " AND id=?", args(actor, id))
        == 0) throw ResourceProblem.missing();
  }

  private void lockDeletion(Actor actor, String table, String id) {
    // 表名仅来自上面两个固定调用，不接受浏览器输入。
    var row =
        jdbc
            .query(
                "SELECT task_id,owner_id FROM " + table + " WHERE " + SCOPE + " AND id=?",
                (r, n) -> new long[] {r.getLong(1), r.getLong(2)},
                args(actor, id))
            .stream()
            .findFirst()
            .orElseThrow(ResourceProblem::missing);
    LessonWriteGuard.lockWritable(jdbc, actor.tenantId(), row[0], row[1]);
  }
}
