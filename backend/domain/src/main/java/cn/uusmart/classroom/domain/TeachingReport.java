package cn.uusmart.classroom.domain;

import java.time.Instant;

public record TeachingReport(
    String id,
    long tenantId,
    long ownerId,
    long taskId,
    String title,
    String content,
    String source,
    Instant createdAt) {}
