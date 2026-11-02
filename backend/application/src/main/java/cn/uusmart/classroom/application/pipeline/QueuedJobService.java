package cn.uusmart.classroom.application.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import java.time.Instant;
import java.util.UUID;

public final class QueuedJobService {
  private final boolean enabled;
  private final PipelineRepository pipeline;
  private final MediaRepository media;
  private final LockedModels models;
  private final JobArtifacts artifacts;

  public QueuedJobService(
      boolean enabled,
      PipelineRepository pipeline,
      MediaRepository media,
      LockedModels models,
      JobArtifacts artifacts) {
    this.enabled = enabled;
    this.pipeline = pipeline;
    this.media = media;
    this.models = models;
    this.artifacts = artifacts;
  }

  public boolean enabled() {
    return enabled;
  }

  public DetectionJob submit(
      Actor actor, LessonTask task, MediaAsset asset, CatalogEntry model, String kind) {
    if (!models.version().equals(model.code())) throw ResourceProblem.invalid("该模型档案没有对应已锁定的推理版本");
    String node =
        pipeline
            .availableNode(actor.tenantId())
            .orElseThrow(() -> new ServiceUnavailable("本校没有启用的边缘节点"));
    String id = UUID.randomUUID().toString();
    pipeline.enqueue(
        new DetectionJob(
            id,
            actor.tenantId(),
            task.ownerId(),
            task.id(),
            task.name(),
            asset.id(),
            model.id(),
            model.code(),
            "QUEUED",
            null,
            Instant.now()),
        node,
        kind);
    return media.job(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public void removeArtifacts(long tenantId, String jobId) {
    artifacts.removeJob(tenantId, jobId);
  }
}
