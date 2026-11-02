package cn.uusmart.classroom.application;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int size) {
  public static void validate(String query, int page, int size) {
    if (page < 1
        || page > 100000
        || size < 1
        || size > 100
        || query == null
        || query.length() > 128) throw ResourceProblem.invalid("分页或查询参数无效");
  }
}
