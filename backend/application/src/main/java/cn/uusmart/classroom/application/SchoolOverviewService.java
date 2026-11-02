package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.util.Map;

public final class SchoolOverviewService {
  private final SchoolOverviewRepository repository;

  public SchoolOverviewService(SchoolOverviewRepository repository) {
    this.repository = repository;
  }

  public Map<String, Long> dashboard(Actor actor) {
    TeachingService.requireSchoolUser(actor);
    return repository.dashboard(actor);
  }

  public Page<OperationLog> logs(Actor actor, String q, int page, int size) {
    TeachingService.requireAdmin(actor);
    Page.validate(q, page, size);
    return repository.logs(actor, q, page, size);
  }

  public void append(Actor actor, String method, String path, int status, String requestId) {
    repository.append(actor, method, path, status, requestId);
  }
}
