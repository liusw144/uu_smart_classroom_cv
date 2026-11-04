package cn.uusmart.classroom.domain;

import java.time.Instant;

public record LessonTaskEvent(
    long id,
    long actorId,
    String actorName,
    String action,
    String fromStatus,
    String toStatus,
    long version,
    Instant occurredAt,
    String note) {}
