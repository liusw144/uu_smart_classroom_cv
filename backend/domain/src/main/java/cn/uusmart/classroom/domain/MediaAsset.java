package cn.uusmart.classroom.domain;

public record MediaAsset(
    String id,
    long tenantId,
    long ownerId,
    String filename,
    String contentType,
    long size,
    String sha256) {}
