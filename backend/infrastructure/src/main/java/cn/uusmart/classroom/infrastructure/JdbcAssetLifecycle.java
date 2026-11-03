package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class JdbcAssetLifecycle implements AssetLifecycle {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final FileAssetStorage files;
  private final long ownerBytes,
      schoolBytes,
      globalBytes,
      ownerFiles,
      schoolFiles,
      globalFiles,
      minimumFree;
  private final int staleSeconds;

  public JdbcAssetLifecycle(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      FileAssetStorage files,
      @Value("${classroom.storage.owner-bytes:1073741824}") long ownerBytes,
      @Value("${classroom.storage.school-bytes:10737418240}") long schoolBytes,
      @Value("${classroom.storage.global-bytes:107374182400}") long globalBytes,
      @Value("${classroom.storage.owner-files:10000}") long ownerFiles,
      @Value("${classroom.storage.school-files:50000}") long schoolFiles,
      @Value("${classroom.storage.global-files:500000}") long globalFiles,
      @Value("${classroom.storage.minimum-free-bytes:2147483648}") long minimumFree,
      @Value("${classroom.storage.pending-seconds:900}") int staleSeconds) {
    if (ownerBytes < 1
        || schoolBytes < 1
        || globalBytes < 1
        || globalBytes > Long.MAX_VALUE / 2
        || ownerFiles < 1
        || schoolFiles < 1
        || globalFiles < 1
        || minimumFree < 1
        || staleSeconds < 5) throw new IllegalArgumentException("存储预算配置无效");
    this.jdbc = jdbc;
    this.files = files;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(60);
    this.ownerBytes = ownerBytes;
    this.schoolBytes = schoolBytes;
    this.globalBytes = globalBytes;
    this.ownerFiles = ownerFiles;
    this.schoolFiles = schoolFiles;
    this.globalFiles = globalFiles;
    this.minimumFree = minimumFree;
    this.staleSeconds = staleSeconds;
  }

  private static long charge(long bytes) {
    return Math.max(8192, bytes);
  }

  private void lockBudget() {
    jdbc.queryForObject(
        "SELECT used_bytes FROM media_storage_usage WHERE tenant_id=0 AND owner_id=0 FOR UPDATE",
        Long.class);
  }

  private void allocate(long tenant, long owner, long bytes, long limit, long countLimit) {
    jdbc.update(
        "INSERT IGNORE INTO media_storage_usage(tenant_id,owner_id) VALUES (?,?)", tenant, owner);
    var current =
        jdbc.queryForMap(
            "SELECT used_bytes,file_count FROM media_storage_usage WHERE tenant_id=? AND owner_id=?",
            tenant,
            owner);
    if (((Number) current.get("used_bytes")).longValue() > limit - bytes
        || ((Number) current.get("file_count")).longValue() >= countLimit)
      throw new StorageLimit(false);
    jdbc.update(
        "UPDATE media_storage_usage SET used_bytes=used_bytes+?,file_count=file_count+1 WHERE tenant_id=? AND owner_id=?",
        bytes,
        tenant,
        owner);
  }

  private void reserve(MediaAsset a) {
    tx.executeWithoutResult(
        status -> {
          lockBudget();
          long pending =
              jdbc.queryForObject(
                  "SELECT COALESCE(SUM(size_bytes),0) FROM media_asset WHERE storage_state='PENDING'",
                  Long.class);
          files.requireCapacity(pending, a.size(), minimumFree);
          long charged = charge(a.size());
          allocate(0, 0, charged, globalBytes, globalFiles);
          allocate(a.tenantId(), 0, charged, schoolBytes, schoolFiles);
          allocate(a.tenantId(), a.ownerId(), charged, ownerBytes, ownerFiles);
          jdbc.update(
              "INSERT INTO media_asset(id,tenant_id,owner_id,filename,content_type,size_bytes,sha256,storage_state) VALUES (?,?,?,?,?,?,?,'PENDING')",
              a.id(),
              a.tenantId(),
              a.ownerId(),
              a.filename(),
              a.contentType(),
              a.size(),
              a.sha256());
        });
  }

  public void create(MediaAsset a, byte[] content) {
    if (a.tenantId() < 1
        || a.ownerId() < 1
        || a.size() != content.length
        || content.length < 1
        || content.length > 32 * 1024 * 1024) throw ResourceProblem.invalid("素材内容与记账信息不一致");
    reserve(a);
    try {
      files.exclusively(
          a.tenantId(),
          a.id(),
          () ->
              tx.execute(
                  status -> {
                    // 数据库状态与文件锁一起阻止已回收的暂停上传重新发布。
                    var state =
                        jdbc.queryForList(
                            "SELECT storage_state FROM media_asset WHERE tenant_id=? AND id=? FOR UPDATE",
                            String.class,
                            a.tenantId(),
                            a.id());
                    if (!state.equals(List.of("PENDING")))
                      throw ResourceProblem.conflict("素材预留已失效");
                    files.write(a.tenantId(), a.id(), content);
                    jdbc.update(
                        "UPDATE media_asset SET storage_state='READY' WHERE tenant_id=? AND id=? AND storage_state='PENDING'",
                        a.tenantId(),
                        a.id());
                    return null;
                  }));
    } catch (RuntimeException error) {
      try {
        files.exclusively(
            a.tenantId(),
            a.id(),
            () -> {
              retirePending(a.tenantId(), a.id(), false);
              drain(a.tenantId(), a.id());
              return null;
            });
      } catch (RuntimeException cleanup) {
        logCleanupFailure();
      }
      throw error;
    }
  }

  public void delete(Actor actor, String id) {
    files.exclusively(
        actor.tenantId(),
        id,
        () -> {
          tx.executeWithoutResult(
              status -> {
                var rows =
                    jdbc.queryForList(
                        "SELECT owner_id,size_bytes FROM media_asset WHERE tenant_id=? AND id=? AND storage_state='READY' AND (?=1 OR owner_id=?) FOR UPDATE",
                        actor.tenantId(),
                        id,
                        actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
                        actor.userId());
                if (rows.isEmpty()) throw ResourceProblem.missing();
                // DELETE先受头像/作业外键约束，不能先删文件。
                jdbc.update(
                    "DELETE FROM media_asset WHERE tenant_id=? AND id=?", actor.tenantId(), id);
                enqueue(
                    actor.tenantId(),
                    ((Number) rows.getFirst().get("owner_id")).longValue(),
                    id,
                    ((Number) rows.getFirst().get("size_bytes")).longValue());
              });
          drain(actor.tenantId(), id);
          return null;
        });
  }

  private void enqueue(long tenant, long owner, String id, long bytes) {
    jdbc.update(
        "INSERT INTO media_storage_cleanup(asset_id,tenant_id,owner_id,charged_bytes) VALUES (?,?,?,?)",
        id,
        tenant,
        owner,
        charge(bytes));
  }

  private void retirePending(long tenant, String id, boolean onlyStale) {
    tx.executeWithoutResult(
        status -> {
          var rows =
              jdbc.queryForList(
                  "SELECT owner_id,size_bytes FROM media_asset WHERE tenant_id=? AND id=? AND storage_state='PENDING'"
                      + (onlyStale
                          ? " AND created_at < TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6))"
                          : "")
                      + " FOR UPDATE",
                  onlyStale ? new Object[] {tenant, id, -staleSeconds} : new Object[] {tenant, id});
          if (rows.isEmpty()) return;
          jdbc.update(
              "DELETE FROM media_asset WHERE tenant_id=? AND id=? AND storage_state='PENDING'",
              tenant,
              id);
          enqueue(
              tenant,
              ((Number) rows.getFirst().get("owner_id")).longValue(),
              id,
              ((Number) rows.getFirst().get("size_bytes")).longValue());
        });
  }

  private void drain(long tenant, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT owner_id,charged_bytes FROM media_storage_cleanup WHERE tenant_id=? AND asset_id=?",
            tenant,
            id);
    if (rows.isEmpty()) return;
    files.remove(tenant, id);
    tx.executeWithoutResult(
        status -> {
          lockBudget();
          var cleanup =
              jdbc.queryForList(
                  "SELECT owner_id,charged_bytes FROM media_storage_cleanup WHERE tenant_id=? AND asset_id=? FOR UPDATE",
                  tenant,
                  id);
          if (cleanup.isEmpty()) return;
          long owner = ((Number) cleanup.getFirst().get("owner_id")).longValue();
          long bytes = ((Number) cleanup.getFirst().get("charged_bytes")).longValue();
          for (long[] key :
              List.of(new long[] {0, 0}, new long[] {tenant, 0}, new long[] {tenant, owner})) {
            if (jdbc.update(
                    "UPDATE media_storage_usage SET used_bytes=used_bytes-?,file_count=file_count-1 WHERE tenant_id=? AND owner_id=? AND used_bytes>=? AND file_count>0",
                    bytes,
                    key[0],
                    key[1],
                    bytes)
                != 1) throw new IllegalStateException("存储账本不一致，保持保守占用并等待排查");
          }
          jdbc.update(
              "DELETE FROM media_storage_cleanup WHERE tenant_id=? AND asset_id=?", tenant, id);
        });
  }

  @Scheduled(
      fixedDelayString = "${classroom.storage.cleanup-delay-ms:30000}",
      initialDelayString = "${classroom.storage.cleanup-delay-ms:30000}")
  public void recover() {
    var rows =
        jdbc.queryForList(
            "SELECT tenant_id,id AS asset_id FROM media_asset WHERE storage_state='PENDING' AND created_at < TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6)) UNION ALL SELECT tenant_id,asset_id FROM media_storage_cleanup ORDER BY asset_id LIMIT 100",
            -staleSeconds);
    for (var row : rows) {
      long tenant = ((Number) row.get("tenant_id")).longValue();
      String id = (String) row.get("asset_id");
      try {
        files.exclusively(
            tenant,
            id,
            () -> {
              retirePending(tenant, id, true);
              drain(tenant, id);
              return null;
            });
      } catch (ResourceProblem busy) {
        // 活动写入持有文件锁，不能提前回收或释放额度。
      } catch (RuntimeException error) {
        logCleanupFailure();
      }
    }
  }

  private void logCleanupFailure() {
    org.slf4j.LoggerFactory.getLogger(getClass()).warn("素材清理未完成，保留配额并等待重试");
  }
}
