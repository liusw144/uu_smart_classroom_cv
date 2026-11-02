package cn.uusmart.classroom.application.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import cn.uusmart.classroom.domain.pipeline.EdgeNodeStatus;

public final class NodeStatusService {
  private final PipelineRepository repository;

  public NodeStatusService(PipelineRepository repository) {
    this.repository = repository;
  }

  public Page<EdgeNodeStatus> list(Actor actor, String q, int page, int size) {
    TeachingService.requireSchoolUser(actor);
    if (actor.role() != Role.SCHOOL_ADMIN) throw new AccessDenied();
    Page.validate(q, page, size);
    return repository.nodes(actor.tenantId(), q, page, size);
  }
}
