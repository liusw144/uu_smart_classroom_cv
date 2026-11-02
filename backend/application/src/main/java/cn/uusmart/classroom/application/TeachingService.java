package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.time.Instant;

public final class TeachingService {
  private final TeachingRepository repository;
  private final AccountRepository accounts;

  public TeachingService(TeachingRepository repository, AccountRepository accounts) {
    this.repository = repository;
    this.accounts = accounts;
  }

  public static void requireSchoolUser(Actor actor) {
    if (actor.role() == Role.PLATFORM_OPERATOR) throw new AccessDenied();
  }

  public static void requireAdmin(Actor actor) {
    if (actor.role() != Role.SCHOOL_ADMIN) throw new AccessDenied();
  }

  public Page<SchoolClass> classes(Actor actor, String query, int page, int size) {
    requireSchoolUser(actor);
    Page.validate(query, page, size);
    return repository.classes(actor, query, page, size);
  }

  public SchoolClass schoolClass(Actor actor, long id) {
    requireSchoolUser(actor);
    return repository.schoolClass(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public SchoolClass saveClass(
      Actor actor, Long id, Long ownerId, String name, String grade, int count) {
    requireSchoolUser(actor);
    long owner = ownerId == null ? actor.userId() : ownerId;
    if (actor.role() == Role.TEACHER && owner != actor.userId()) throw new AccessDenied();
    if (id != null) {
      var previous = schoolClass(actor, id);
      if (ownerId == null) owner = previous.ownerId();
      if (owner != previous.ownerId()) throw ResourceProblem.conflict("班级负责人暂不支持变更，请先处理课程关系");
    }
    var account = accounts.findActiveById(owner).orElseThrow(ResourceProblem::missing);
    if (account.tenantId() != actor.tenantId() || account.role() == Role.PLATFORM_OPERATOR)
      throw ResourceProblem.missing();
    return schoolClass(actor, repository.saveClass(actor, id, owner, name, grade, count));
  }

  public void deleteClass(Actor actor, long id) {
    schoolClass(actor, id);
    repository.deleteClass(actor, id);
  }

  public Page<Course> courses(Actor actor, String query, int page, int size) {
    requireSchoolUser(actor);
    Page.validate(query, page, size);
    return repository.courses(actor, query, page, size);
  }

  public Course course(Actor actor, long id) {
    requireSchoolUser(actor);
    return repository.course(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public Course saveCourse(Actor actor, Long id, String name, long classId, String term) {
    var parent = schoolClass(actor, classId);
    if (id != null) course(actor, id);
    return course(actor, repository.saveCourse(actor, id, parent.ownerId(), name, classId, term));
  }

  public void deleteCourse(Actor actor, long id) {
    course(actor, id);
    repository.deleteCourse(actor, id);
  }

  public Page<LessonTask> tasks(Actor actor, String query, int page, int size) {
    requireSchoolUser(actor);
    Page.validate(query, page, size);
    return repository.tasks(actor, query, page, size);
  }

  public LessonTask task(Actor actor, long id) {
    requireSchoolUser(actor);
    return repository.task(actor, id).orElseThrow(ResourceProblem::missing);
  }

  public LessonTask saveTask(
      Actor actor, Long id, String name, long courseId, Instant scheduledAt) {
    var parent = course(actor, courseId);
    if (id != null) task(actor, id);
    return task(
        actor, repository.saveTask(actor, id, parent.ownerId(), name, courseId, scheduledAt));
  }

  public void deleteTask(Actor actor, long id) {
    task(actor, id);
    repository.deleteTask(actor, id);
  }
}
