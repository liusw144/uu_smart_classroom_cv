package cn.uusmart.classroom.domain;

import java.time.Instant;

public record LessonTask(
    long id,
    long tenantId,
    long ownerId,
    String name,
    long courseId,
    long classId,
    Instant scheduledAt,
    Instant scheduledEndAt,
    String roomName,
    String status,
    Instant actualStartedAt,
    Instant actualEndedAt,
    Instant archivedAt,
    long version,
    boolean metadataLocked,
    LessonSnapshot snapshot) {}
