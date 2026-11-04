package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.util.Optional;

public interface TeachingRepository {
  Page<SchoolClass> classes(Actor actor, String query, int page, int size);

  Optional<SchoolClass> schoolClass(Actor actor, long id);

  long saveClass(Actor actor, Long id, long ownerId, String name, String grade, int studentCount);

  void deleteClass(Actor actor, long id);

  Page<Course> courses(Actor actor, String query, int page, int size);

  Optional<Course> course(Actor actor, long id);

  long saveCourse(Actor actor, Long id, long ownerId, String name, long classId, String term);

  void deleteCourse(Actor actor, long id);
}
