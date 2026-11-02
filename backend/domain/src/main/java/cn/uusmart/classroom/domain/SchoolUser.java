package cn.uusmart.classroom.domain;

public record SchoolUser(
    long id,
    long tenantId,
    String username,
    String displayName,
    String email,
    Role role,
    boolean enabled,
    String avatarAssetId) {}
