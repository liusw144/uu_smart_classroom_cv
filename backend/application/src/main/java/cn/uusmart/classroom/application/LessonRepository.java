package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.time.Instant;
import java.util.Optional;

public interface LessonRepository {
  Page<LessonTask> tasks(Actor actor, String query, int page, int size);

  Optional<LessonTask> task(Actor actor, long id);

  LessonTask save(Actor actor, Long id, LessonTaskEdit edit);

  LessonTask transition(Actor actor, long id, String action, long version, Instant occurredAt);

  Page<LessonTaskEvent> events(Actor actor, long id, int page, int size);

  void delete(Actor actor, long id);
}
