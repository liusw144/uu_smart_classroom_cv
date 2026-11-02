package cn.uusmart.classroom.domain.pipeline;

import java.time.Instant;

public record EdgeNodeStatus(
    String id,
    String name,
    boolean enabled,
    boolean online,
    Instant lastSeenAt,
    long queued,
    long running) {}
