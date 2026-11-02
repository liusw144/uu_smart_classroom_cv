package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.PipelineRepository;
import cn.uusmart.classroom.domain.*;
import cn.uusmart.classroom.domain.pipeline.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPipelineRepository implements PipelineRepository {
  private final JdbcTemplate jdbc;
  private static final String SELECT =
      "SELECT j.*,a.sha256 AS input_sha256,a.filename AS asset_filename,a.content_type AS asset_type,a.size_bytes AS asset_size FROM detection_job j JOIN media_asset a ON a.id=j.asset_id AND a.tenant_id=j.tenant_id AND a.owner_id=j.owner_id ";
  private static final RowMapper<StoredJob> JOB =
      (r, n) ->
          new StoredJob(
              new JobLease(
                  r.getString("id"),
                  r.getString("asset_id"),
                  r.getString("lease_id"),
                  r.getInt("attempt"),
                  r.getTimestamp("lease_until") == null
                      ? null
                      : r.getTimestamp("lease_until").toInstant(),
                  r.getString("input_sha256"),
                  r.getString("model_version"),
                  r.getString("media_kind"),
                  r.getString("asset_filename"),
                  r.getString("asset_type"),
                  r.getLong("asset_size")),
              r.getLong("tenant_id"),
              r.getLong("owner_id"),
              r.getString("node_id"),
              r.getString("status"),
              r.getString("result_hash"),
              r.getString("result_json"),
              r.getString("artifact_content_type"));

  public JdbcPipelineRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<NodeCredential> node(String id) {
    return jdbc
        .query(
            "SELECT n.id,n.tenant_id,n.token_hash FROM edge_node n JOIN tenant t ON t.id=n.tenant_id WHERE n.id=? AND n.enabled=true AND t.enabled=true",
            (r, n) ->
                new NodeCredential(
                    new NodePrincipal(r.getString("id"), r.getLong("tenant_id")),
                    r.getString("token_hash")),
            id)
        .stream()
        .findFirst();
  }

  public Optional<String> availableNode(long tenant) {
    return jdbc
        .query(
            "SELECT id FROM edge_node WHERE tenant_id=? AND enabled=true AND last_seen_at>=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 90 SECOND) ORDER BY id LIMIT 1",
            (r, n) -> r.getString("id"),
            tenant)
        .stream()
        .findFirst();
  }

  public void enqueue(DetectionJob j, String node, String kind) {
    jdbc.update(
        "INSERT INTO detection_job(id,tenant_id,owner_id,task_id,asset_id,model_id,model_version,status,created_at,node_id,media_kind) VALUES (?,?,?,?,?,?,?,'QUEUED',?,?,?)",
        j.id(),
        j.tenantId(),
        j.ownerId(),
        j.taskId(),
        j.assetId(),
        j.modelId(),
        j.modelVersion(),
        Timestamp.from(j.createdAt()),
        node,
        kind);
  }

  public cn.uusmart.classroom.application.Page<EdgeNodeStatus> nodes(
      long tenant, String q, int page, int size) {
    String filter = " WHERE n.tenant_id=? AND (n.name LIKE ? OR n.id LIKE ?)";
    String pattern = "%" + q + "%";
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM edge_node n" + filter, Long.class, tenant, pattern, pattern);
    var items =
        jdbc.query(
            "SELECT n.id,n.name,n.enabled,n.last_seen_at,(n.enabled AND n.last_seen_at>=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 90 SECOND)) AS online,(SELECT COUNT(*) FROM detection_job j WHERE j.tenant_id=n.tenant_id AND j.node_id=n.id AND j.status='QUEUED') AS queued,(SELECT COUNT(*) FROM detection_job j WHERE j.tenant_id=n.tenant_id AND j.node_id=n.id AND j.status='RUNNING') AS running FROM edge_node n"
                + filter
                + " ORDER BY n.id LIMIT ? OFFSET ?",
            (r, i) ->
                new EdgeNodeStatus(
                    r.getString("id"),
                    r.getString("name"),
                    r.getBoolean("enabled"),
                    r.getBoolean("online"),
                    r.getTimestamp("last_seen_at") == null
                        ? null
                        : r.getTimestamp("last_seen_at").toInstant(),
                    r.getLong("queued"),
                    r.getLong("running")),
            tenant,
            pattern,
            pattern,
            size,
            (page - 1) * size);
    return new cn.uusmart.classroom.application.Page<>(
        items, total == null ? 0 : total, page, size);
  }

  public Optional<StoredJob> nodeJob(NodePrincipal node, String id) {
    return jdbc
        .query(
            SELECT + " WHERE j.tenant_id=? AND j.node_id=? AND j.id=?",
            JOB,
            node.tenantId(),
            node.id(),
            id)
        .stream()
        .findFirst();
  }

  public Optional<StoredJob> userJob(Actor actor, String id) {
    return jdbc
        .query(
            SELECT + " WHERE j.tenant_id=? AND (?=1 OR j.owner_id=?) AND j.id=?",
            JOB,
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId(),
            id)
        .stream()
        .findFirst();
  }

  @Transactional
  public Optional<JobLease> claim(NodePrincipal node) {
    jdbc.update(
        "UPDATE edge_node SET last_seen_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
        node.tenantId(),
        node.id());
    jdbc.update(
        "UPDATE detection_job SET status='FAILED',error_code='LEASE_EXHAUSTED' WHERE tenant_id=? AND node_id=? AND status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6) AND attempt>=3",
        node.tenantId(),
        node.id());
    var running =
        jdbc.query(
            "SELECT id FROM detection_job WHERE tenant_id=? AND node_id=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6) ORDER BY created_at LIMIT 1 FOR UPDATE",
            (r, n) -> r.getString("id"),
            node.tenantId(),
            node.id());
    if (!running.isEmpty()) return nodeJob(node, running.getFirst()).map(StoredJob::lease);
    var waiting =
        jdbc.query(
            "SELECT id FROM detection_job WHERE tenant_id=? AND node_id=? AND attempt<3 AND (status='QUEUED' OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6))) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
            (r, n) -> r.getString("id"),
            node.tenantId(),
            node.id());
    if (waiting.isEmpty()) return Optional.empty();
    String id = waiting.getFirst();
    jdbc.update(
        "UPDATE detection_job SET status='RUNNING',lease_id=?,lease_until=?,attempt=attempt+1,error_code=NULL WHERE tenant_id=? AND node_id=? AND id=?",
        UUID.randomUUID().toString(),
        Timestamp.from(Instant.now().plusSeconds(600)),
        node.tenantId(),
        node.id(),
        id);
    return nodeJob(node, id).map(StoredJob::lease);
  }

  public boolean complete(
      NodePrincipal node, String id, String lease, String hash, String result, String type) {
    return jdbc.update(
            "UPDATE detection_job SET status='SUCCEEDED',error_code=NULL,result_hash=?,result_json=?,artifact_content_type=? WHERE tenant_id=? AND node_id=? AND id=? AND lease_id=? AND status='RUNNING' AND lease_until>=CURRENT_TIMESTAMP(6)",
            hash,
            result,
            type,
            node.tenantId(),
            node.id(),
            id,
            lease)
        == 1;
  }

  public boolean fail(NodePrincipal node, String id, String lease, String code) {
    return jdbc.update(
            "UPDATE detection_job SET status='FAILED',error_code=? WHERE tenant_id=? AND node_id=? AND id=? AND lease_id=? AND status='RUNNING' AND lease_until>=CURRENT_TIMESTAMP(6)",
            code,
            node.tenantId(),
            node.id(),
            id,
            lease)
        == 1;
  }

  public void heartbeat(NodePrincipal node, String id, String lease) {
    if (jdbc.update(
            "UPDATE detection_job SET lease_until=? WHERE tenant_id=? AND node_id=? AND id=? AND lease_id=? AND status='RUNNING' AND lease_until>=CURRENT_TIMESTAMP(6)",
            Timestamp.from(Instant.now().plusSeconds(600)),
            node.tenantId(),
            node.id(),
            id,
            lease)
        == 0) throw ResourceProblem.conflict("租约已失效");
    jdbc.update(
        "UPDATE edge_node SET last_seen_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
        node.tenantId(),
        node.id());
  }

  public void cancel(Actor actor, String id) {
    var job = userJob(actor, id).orElseThrow(ResourceProblem::missing);
    if ("CANCELLED".equals(job.status())) return;
    if (jdbc.update(
            "UPDATE detection_job SET status='CANCELLED',error_code='USER_CANCELLED' WHERE tenant_id=? AND (?=1 OR owner_id=?) AND id=? AND status IN ('QUEUED','RUNNING')",
            actor.tenantId(),
            actor.role() == Role.SCHOOL_ADMIN ? 1 : 0,
            actor.userId(),
            id)
        == 0) throw ResourceProblem.conflict("已结束的作业不能取消");
  }

  public void createAiReport(TeachingReport r, String jobId, String model, String hash) {
    jdbc.update(
        "INSERT INTO analysis_report(id,tenant_id,owner_id,task_id,title,content,source,created_at,detection_job_id,ai_model,ai_weight_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        r.id(),
        r.tenantId(),
        r.ownerId(),
        r.taskId(),
        r.title(),
        r.content(),
        r.source(),
        Timestamp.from(r.createdAt()),
        jobId,
        model,
        hash);
  }
}
