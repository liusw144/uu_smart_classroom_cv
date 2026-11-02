package cn.uusmart.classroom.domain;

public record Notice(long id, long tenantId, String title, String content, boolean published) {}
