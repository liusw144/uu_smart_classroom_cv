package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.util.Map;

public interface SchoolOverviewRepository {
  Map<String, Long> dashboard(Actor actor);

  Page<OperationLog> logs(Actor actor, String query, int page, int size);

  void append(Actor actor, String method, String path, int status, String requestId);
}
