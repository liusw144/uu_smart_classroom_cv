package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LessonHistoryIT extends SchoolHttpITSupport {
  long course(long classId, String name) throws Exception {
    return data(call(
            "POST",
            "/courses",
            json.writeValueAsString(Map.of("name", name, "class_id", classId, "term", "秋季")),
            teacher))
        .path("id")
        .asLong();
  }

  JsonNode task(long courseId) throws Exception {
    return data(
        call(
            "POST",
            "/tasks",
            json.writeValueAsString(Map.of("name", "课堂历史验证", "course_id", courseId)),
            teacher));
  }

  java.net.http.HttpResponse<String> transition(
      JsonNode task, String action, String time, String token) throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("action", action);
    body.put("version", task.path("version").asLong());
    if (time != null) body.put("occurred_at", time);
    return call(
        "POST",
        "/tasks/" + task.path("id").asLong() + "/transition",
        json.writeValueAsString(body),
        token);
  }

  @Test
  void historicalClassAndNamesDoNotFollowCurrentCourseAndProfileChanges() throws Exception {
    long original = schoolClass(teacher);
    long courseId = course(original, "原课程");
    var task = task(courseId);
    assertEquals("CREATED", task.path("snapshot").path("source").asText());
    String ownerName = task.path("snapshot").path("owner_name").asText();
    long replacement = schoolClass(teacher);
    data(
        call(
            "PUT",
            "/courses/" + courseId,
            json.writeValueAsString(Map.of("name", "新课程", "class_id", replacement, "term", "春季")),
            teacher));
    data(
        call(
            "PUT",
            "/classes/" + original,
            "{\"name\":\"新班名\",\"grade\":\"高三\",\"student_count\":50}",
            teacher));
    jdbc.update("UPDATE school_account SET display_name='改名教师' WHERE id=1101");
    var historical = data(call("GET", "/tasks/" + task.path("id").asLong(), null, teacher));
    assertEquals(original, historical.path("class_id").asLong());
    assertEquals("原课程", historical.path("snapshot").path("course_name").asText());
    assertEquals("一班", historical.path("snapshot").path("class_name").asText());
    assertEquals(ownerName, historical.path("snapshot").path("owner_name").asText());
    String report =
        data(call(
                "POST",
                "/reports",
                json.writeValueAsString(
                    Map.of("task_id", task.path("id").asLong(), "title", "历史快照报告")),
                teacher))
            .path("content")
            .asText();
    assertTrue(report.contains("原课程"));
    assertFalse(report.contains("新课程"));
    assertFalse(report.contains("新班名"));
  }

  @Test
  void concurrentStartOnlyChangesOneVersionAndOneEvent() throws Exception {
    var task = task(course(schoolClass(teacher), "并发课堂"));
    var first =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                return transition(task, "START", "2026-01-01T09:00:00Z", teacher).statusCode();
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
            });
    var second =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                return transition(task, "START", "2026-01-01T09:00:00Z", teacher).statusCode();
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
            });
    assertEquals(java.util.Set.of(200, 409), java.util.Set.of(first.get(), second.get()));
    var current = data(call("GET", "/tasks/" + task.path("id").asLong(), null, teacher));
    assertEquals(1, current.path("version").asLong());
    assertTrue(current.path("metadata_locked").asBoolean());
    assertEquals(
        2,
        data(call("GET", "/tasks/" + task.path("id").asLong() + "/events", null, teacher))
            .path("total")
            .asInt());
  }

  @Test
  void explicitDraftCourseChangeRecapturesSnapshotAndCannotCrossSchool() throws Exception {
    var task = task(course(schoolClass(teacher), "原计划"));
    long replacement = course(schoolClass(teacher), "重选课程");
    var saved =
        data(
            call(
                "PUT",
                "/tasks/" + task.path("id").asLong(),
                json.writeValueAsString(
                    Map.of("name", "重选计划", "course_id", replacement, "version", 0)),
                teacher));
    assertEquals("REASSIGNED", saved.path("snapshot").path("source").asText());
    assertEquals("重选课程", saved.path("snapshot").path("course_name").asText());
    assertEquals(
        404,
        call(
                "PUT",
                "/tasks/" + task.path("id").asLong(),
                json.writeValueAsString(
                    Map.of("name", "跨校修改", "course_id", replacement, "version", 1)),
                other)
            .statusCode());
    assertEquals(400, transition(saved, "START", "2099-01-01T00:00:00Z", teacher).statusCode());
  }

  @Test
  void activeJobPreventsArchiveUntilCancellation() throws Exception {
    var task = task(course(schoolClass(teacher), "活动作业课堂"));
    var started = data(transition(task, "START", "2026-01-01T09:00:00Z", teacher));
    var completed = data(transition(started, "COMPLETE", "2026-01-01T10:00:00Z", teacher));
    String asset = java.util.UUID.randomUUID().toString(),
        job = java.util.UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO media_asset(id,tenant_id,owner_id,filename,content_type,size_bytes,sha256) VALUES (?,101,1101,'fixture.png','image/png',1,?)",
        asset,
        "a".repeat(64));
    long model =
        data(call(
                "POST",
                "/models",
                "{\"name\":\"活动作业\",\"code\":\"pending\",\"description\":\"\",\"enabled\":true}",
                admin))
            .path("id")
            .asLong();
    jdbc.update(
        "INSERT INTO detection_job(id,tenant_id,owner_id,task_id,asset_id,model_id,model_version,status,created_at) VALUES (?,101,1101,?,?,?,'pending','QUEUED',CURRENT_TIMESTAMP(6))",
        job,
        task.path("id").asLong(),
        asset,
        model);
    assertEquals(409, transition(completed, "ARCHIVE", null, teacher).statusCode());
    data(call("POST", "/detections/" + job + "/cancel", null, teacher));
    assertEquals(
        "ARCHIVED", data(transition(completed, "ARCHIVE", null, teacher)).path("status").asText());
  }

  @Test
  void lifecycleValidatesTimeVersionAndPreservesReadOnlyHistory() throws Exception {
    var task = task(course(schoolClass(teacher), "生命周期"));
    assertEquals("PLANNED", task.path("status").asText());
    assertEquals(409, transition(task, "COMPLETE", "2026-01-01T10:00:00Z", teacher).statusCode());
    var running = data(transition(task, "START", "2026-01-01T09:00:00Z", teacher));
    assertEquals("IN_PROGRESS", running.path("status").asText());
    assertEquals(409, transition(task, "START", null, teacher).statusCode());
    assertEquals(
        400, transition(running, "COMPLETE", "2026-01-01T08:00:00Z", teacher).statusCode());
    var completed = data(transition(running, "COMPLETE", "2026-01-01T10:00:00Z", teacher));
    var archived = data(transition(completed, "ARCHIVE", null, teacher));
    assertEquals("ARCHIVED", archived.path("status").asText());
    long id = archived.path("id").asLong();
    assertEquals(409, call("DELETE", "/tasks/" + id, null, teacher).statusCode());
    assertEquals(
        409,
        call(
                "PUT",
                "/tasks/" + id,
                json.writeValueAsString(
                    Map.of("name", "试图改写", "course_id", task.path("course_id").asLong())),
                teacher)
            .statusCode());
    var events = data(call("GET", "/tasks/" + id + "/events", null, teacher));
    assertEquals(4, events.path("total").asInt());
    assertEquals(404, call("GET", "/tasks/" + id + "/events", null, peer).statusCode());
    assertEquals(404, transition(archived, "START", null, other).statusCode());
  }

  @Test
  void plannedEditRejectsStaleVersionAndInvalidPeriod() throws Exception {
    var task = task(course(schoolClass(teacher), "版本校验"));
    long id = task.path("id").asLong(), courseId = task.path("course_id").asLong();
    String input =
        json.writeValueAsString(
            Map.of(
                "name",
                "计划教室",
                "course_id",
                courseId,
                "version",
                task.path("version").asLong(),
                "room_name",
                "教学楼101",
                "scheduled_at",
                "2026-01-01T09:00:00Z",
                "scheduled_end_at",
                "2026-01-01T10:00:00Z"));
    var saved = data(call("PUT", "/tasks/" + id, input, teacher));
    assertEquals("教学楼101", saved.path("room_name").asText());
    assertEquals(409, call("PUT", "/tasks/" + id, input, teacher).statusCode());
    assertEquals(
        400,
        call(
                "PUT",
                "/tasks/" + id,
                json.writeValueAsString(
                    Map.of(
                        "name",
                        "错误时段",
                        "course_id",
                        courseId,
                        "scheduled_at",
                        "2026-01-01T10:00:00Z",
                        "scheduled_end_at",
                        "2026-01-01T09:00:00Z")),
                teacher)
            .statusCode());
  }

  @Test
  void existingAnalysisFreezesMetadataAndArchivedClassCannotAcceptNewAnalysis() throws Exception {
    var task = task(course(schoolClass(teacher), "分析保护"));
    long id = task.path("id").asLong();
    String asset = java.util.UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO media_asset(id,tenant_id,owner_id,filename,content_type,size_bytes,sha256) VALUES (?,101,1101,'fixture.png','image/png',1,?)",
        asset,
        "a".repeat(64));
    long model =
        data(call(
                "POST",
                "/models",
                "{\"name\":\"测试档案\",\"code\":\"pending\",\"description\":\"\",\"enabled\":true}",
                admin))
            .path("id")
            .asLong();
    String submit =
        json.writeValueAsString(Map.of("task_id", id, "asset_id", asset, "model_id", model));
    String job = data(call("POST", "/detections", submit, teacher)).path("id").asText();
    String report =
        data(call(
                "POST",
                "/reports",
                json.writeValueAsString(Map.of("task_id", id, "title", "归档前报告")),
                teacher))
            .path("id")
            .asText();
    assertEquals(
        409,
        call(
                "PUT",
                "/tasks/" + id,
                json.writeValueAsString(
                    Map.of("name", "不允许改写", "course_id", task.path("course_id").asLong())),
                teacher)
            .statusCode());
    var started = data(transition(task, "START", "2026-01-01T09:00:00Z", teacher));
    var completed = data(transition(started, "COMPLETE", "2026-01-01T10:00:00Z", teacher));
    data(transition(completed, "ARCHIVE", null, teacher));
    assertAll(
        () -> assertEquals(409, call("DELETE", "/reports/" + report, null, teacher).statusCode()),
        () -> assertEquals(409, call("DELETE", "/detections/" + job, null, teacher).statusCode()));
    assertEquals(409, call("POST", "/detections", submit, teacher).statusCode());
    assertEquals(
        409,
        call(
                "POST",
                "/reports",
                json.writeValueAsString(Map.of("task_id", id, "title", "归档后报告")),
                teacher)
            .statusCode());
  }

  @Test
  void legacyReportPermanentlyDisclosesBackfillOrigin() throws Exception {
    var task = task(course(schoolClass(teacher), "历史来源"));
    jdbc.update(
        "UPDATE lesson_task SET snapshot_source='LEGACY_BACKFILL',lifecycle_status='LEGACY' WHERE id=?",
        task.path("id").asLong());
    var report =
        data(
            call(
                "POST",
                "/reports",
                json.writeValueAsString(
                    Map.of("task_id", task.path("id").asLong(), "title", "历史来源报告")),
                teacher));
    String content = report.path("content").asText();
    assertTrue(content.contains("历史初始化快照"));
    assertTrue(content.contains("不能保证代表课堂发生时的原始资料"));
    assertEquals(
        content,
        data(call("GET", "/reports/" + report.path("id").asText(), null, teacher))
            .path("content")
            .asText());
  }

  @Test
  void deletingAnalysisDoesNotUnlockClassroomMetadata() throws Exception {
    var task = task(course(schoolClass(teacher), "永久锁定"));
    long id = task.path("id").asLong();
    String report =
        data(call(
                "POST",
                "/reports",
                json.writeValueAsString(Map.of("task_id", id, "title", "临时报告")),
                teacher))
            .path("id")
            .asText();
    data(call("DELETE", "/reports/" + report, null, teacher));
    assertTrue(
        data(call("GET", "/tasks/" + id, null, teacher)).path("metadata_locked").asBoolean());
    assertEquals(409, call("DELETE", "/tasks/" + id, null, teacher).statusCode());
    assertEquals(
        409,
        call(
                "PUT",
                "/tasks/" + id,
                json.writeValueAsString(
                    Map.of("name", "删除后改写", "course_id", task.path("course_id").asLong())),
                teacher)
            .statusCode());
  }
}
