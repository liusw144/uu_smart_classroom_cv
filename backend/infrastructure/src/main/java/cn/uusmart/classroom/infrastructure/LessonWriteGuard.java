package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.ResourceProblem;
import org.springframework.jdbc.core.JdbcTemplate;

public final class LessonWriteGuard {
  private LessonWriteGuard() {}

  public static void lockWritable(JdbcTemplate jdbc, long tenant, long task, long owner) {
    lockWritable(jdbc, tenant, task, owner, null);
  }

  private record Locked(String status, long version) {}

  public static void lockWritable(
      JdbcTemplate jdbc, long tenant, long task, long owner, Long expectedVersion) {
    // 分析/报告写入或删除与课堂归档共用同一行锁，拒绝检查后再写入的竞态。
    var current =
        jdbc
            .query(
                "SELECT lifecycle_status,row_version FROM lesson_task WHERE tenant_id=? AND id=? AND owner_id=? FOR UPDATE",
                (r, n) -> new Locked(r.getString(1), r.getLong(2)),
                tenant,
                task,
                owner)
            .stream()
            .findFirst()
            .orElseThrow(ResourceProblem::missing);
    if (current.status().equals("ARCHIVED")) throw ResourceProblem.conflict("课堂已归档，相关分析和报告只读");
    if (expectedVersion != null && current.version() != expectedVersion)
      throw ResourceProblem.conflict("课堂已变更，请刷新后重新提交");
    jdbc.update(
        "UPDATE lesson_task SET metadata_frozen=true WHERE tenant_id=? AND id=?", tenant, task);
  }
}
