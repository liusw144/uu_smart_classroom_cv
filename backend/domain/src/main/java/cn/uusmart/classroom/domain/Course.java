package cn.uusmart.classroom.domain;

public record Course(
    long id, long tenantId, long ownerId, String name, long classId, String term) {}
