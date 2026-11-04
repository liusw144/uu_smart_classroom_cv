package cn.uusmart.classroom.domain;

import java.time.Instant;

public record LessonSnapshot(
    String courseName,
    String className,
    String grade,
    int studentCount,
    String term,
    String ownerName,
    Instant capturedAt,
    String source) {}
