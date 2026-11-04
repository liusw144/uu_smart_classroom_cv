package cn.uusmart.classroom.application.pipeline;

import cn.uusmart.classroom.domain.*;
import cn.uusmart.classroom.domain.pipeline.*;
import java.util.Optional;

public interface PipelineRepository {
  record NodeCredential(NodePrincipal principal, String tokenHash) {}

  record StoredJob(
      JobLease lease,
      long tenantId,
      long ownerId,
      String nodeId,
      String status,
      String resultHash,
      String resultJson,
      String artifactType) {}

  Optional<NodeCredential> node(String id);

  Optional<String> availableNode(long tenantId);

  cn.uusmart.classroom.application.Page<EdgeNodeStatus> nodes(
      long tenantId, String q, int page, int size);

  void enqueue(DetectionJob job, String mediaKind, long taskVersion);

  Optional<JobLease> claim(NodePrincipal node);

  Optional<StoredJob> nodeJob(NodePrincipal node, String id);

  Optional<StoredJob> userJob(Actor actor, String id);

  boolean complete(
      NodePrincipal node,
      String jobId,
      String leaseId,
      String resultHash,
      String resultJson,
      String artifactType);

  boolean fail(NodePrincipal node, String jobId, String leaseId, String code);

  void cancel(Actor actor, String id);

  void heartbeat(NodePrincipal node, String id, String leaseId);

  void createAiReport(TeachingReport report, String jobId, String aiModel, String weightHash);
}
