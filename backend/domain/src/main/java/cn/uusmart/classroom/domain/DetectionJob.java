package cn.uusmart.classroom.domain;

import java.time.Instant;

public record DetectionJob(
    String id,
    long tenantId,
    long ownerId,
    long taskId,
    String taskName,
    String assetId,
    long modelId,
    String modelVersion,
    String status,
    String errorCode,
    Instant createdAt) {}
