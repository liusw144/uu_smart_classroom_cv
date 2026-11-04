package cn.uusmart.classroom.application;

import java.time.Instant;

public record LessonTaskEdit(
    String name,
    long courseId,
    Instant scheduledAt,
    Instant scheduledEndAt,
    String roomName,
    Long version) {
  public void validate() {
    if (name == null
        || name.isBlank()
        || name.length() > 128
        || courseId < 1
        || (roomName != null && roomName.length() > 128)
        || (version != null && version < 0)) throw ResourceProblem.invalid("课堂资料无效");
    if (scheduledEndAt != null && (scheduledAt == null || !scheduledEndAt.isAfter(scheduledAt)))
      throw ResourceProblem.invalid("计划结束时间必须晚于计划开始时间");
  }
}
