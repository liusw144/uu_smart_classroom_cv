package cn.uusmart.classroom.domain.pipeline;

import java.time.Instant;

public record JobLease(
    String jobId,
    String assetId,
    String leaseId,
    int attempt,
    Instant leaseExpiresAt,
    String inputSha256,
    String modelVersion,
    String mediaKind,
    String filename,
    String contentType,
    long size) {}
