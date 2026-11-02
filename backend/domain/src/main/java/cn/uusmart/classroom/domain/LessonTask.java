package cn.uusmart.classroom.domain;

import java.time.Instant;

public record LessonTask(
    long id,
    long tenantId,
    long ownerId,
    String name,
    long courseId,
    long classId,
    Instant scheduledAt) {}
