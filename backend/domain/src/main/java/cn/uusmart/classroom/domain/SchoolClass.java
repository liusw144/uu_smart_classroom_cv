package cn.uusmart.classroom.domain;

public record SchoolClass(
    long id, long tenantId, long ownerId, String name, String grade, int studentCount) {}
