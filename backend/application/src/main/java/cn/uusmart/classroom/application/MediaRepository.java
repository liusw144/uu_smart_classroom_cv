package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.util.Optional;

public interface MediaRepository {
  Page<MediaAsset> assets(Actor actor, String query, int page, int size);

  Optional<MediaAsset> asset(Actor actor, String id);

  long taskJobCount(Actor actor, long taskId, String status);

  void createJob(DetectionJob job);

  void failJob(Actor actor, String id, String code);

  Page<DetectionJob> jobs(Actor actor, String query, String status, int page, int size);

  Optional<DetectionJob> job(Actor actor, String id);

  void deleteJob(Actor actor, String id);

  void createReport(TeachingReport report);

  Page<TeachingReport> reports(Actor actor, String query, int page, int size);

  Optional<TeachingReport> report(Actor actor, String id);

  void deleteReport(Actor actor, String id);
}
