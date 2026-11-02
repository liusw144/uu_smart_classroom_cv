package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.util.Optional;

public interface SchoolContentRepository {
  enum Catalog {
    MODEL,
    CATEGORY
  }

  Page<Notice> notices(Actor actor, String query, int page, int size);

  Optional<Notice> notice(Actor actor, long id);

  long saveNotice(Actor actor, Long id, String title, String content, boolean published);

  void deleteNotice(Actor actor, long id);

  Page<CatalogEntry> catalog(Actor actor, Catalog catalog, String query, int page, int size);

  Optional<CatalogEntry> entry(Actor actor, Catalog catalog, long id);

  long saveEntry(
      Actor actor,
      Catalog catalog,
      Long id,
      String name,
      String code,
      String description,
      boolean enabled);

  void deleteEntry(Actor actor, Catalog catalog, long id);
}
