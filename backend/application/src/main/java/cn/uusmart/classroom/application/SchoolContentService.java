package cn.uusmart.classroom.application;

import cn.uusmart.classroom.application.SchoolContentRepository.Catalog;
import cn.uusmart.classroom.domain.*;

public final class SchoolContentService {
  private final SchoolContentRepository repository;

  public SchoolContentService(SchoolContentRepository repository) {
    this.repository = repository;
  }

  public Page<Notice> notices(Actor actor, String q, int page, int size) {
    TeachingService.requireSchoolUser(actor);
    Page.validate(q, page, size);
    return repository.notices(actor, q, page, size);
  }

  public Notice notice(Actor actor, long id) {
    TeachingService.requireSchoolUser(actor);
    return repository.notice(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public Notice saveNotice(Actor actor, Long id, String title, String content, boolean published) {
    TeachingService.requireAdmin(actor);
    if (id != null) notice(actor, id);
    return notice(actor, repository.saveNotice(actor, id, title, content, published));
  }

  public void deleteNotice(Actor actor, long id) {
    TeachingService.requireAdmin(actor);
    notice(actor, id);
    repository.deleteNotice(actor, id);
  }

  public Page<CatalogEntry> catalog(Actor actor, Catalog kind, String q, int page, int size) {
    TeachingService.requireSchoolUser(actor);
    Page.validate(q, page, size);
    return repository.catalog(actor, kind, q, page, size);
  }

  public CatalogEntry entry(Actor actor, Catalog kind, long id) {
    TeachingService.requireSchoolUser(actor);
    return repository.entry(actor, kind, id).orElseThrow(ResourceProblem::missing);
  }

  public CatalogEntry saveEntry(
      Actor actor,
      Catalog kind,
      Long id,
      String name,
      String code,
      String description,
      boolean enabled) {
    TeachingService.requireAdmin(actor);
    if (id != null) entry(actor, kind, id);
    long saved = repository.saveEntry(actor, kind, id, name, code, description, enabled);
    return repository.entry(actor, kind, saved).orElseThrow(ResourceProblem::missing);
  }

  public void deleteEntry(Actor actor, Catalog kind, long id) {
    TeachingService.requireAdmin(actor);
    entry(actor, kind, id);
    repository.deleteEntry(actor, kind, id);
  }
}
