package cn.uusmart.classroom.domain;

import java.time.Instant;

public record OperationLog(
    long id,
    long tenantId,
    long actorId,
    String username,
    String method,
    String path,
    int status,
    String requestId,
    Instant createdAt) {}
