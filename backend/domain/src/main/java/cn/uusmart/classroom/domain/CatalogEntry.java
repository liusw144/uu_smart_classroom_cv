package cn.uusmart.classroom.domain;

public record CatalogEntry(
    long id, long tenantId, String name, String code, String description, boolean enabled) {}
