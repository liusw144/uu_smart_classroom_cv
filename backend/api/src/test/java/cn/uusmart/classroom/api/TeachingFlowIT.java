package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TeachingFlowIT extends SchoolHttpITSupport {
  @Test
  void seedDoesNotReplaceExistingFixtureRows() throws Exception {
    long id = schoolClass(teacher);
    assertThrows(IllegalStateException.class, () -> seed());
    assertEquals(
        1, jdbc.queryForObject("SELECT COUNT(*) FROM school_class WHERE id=?", Integer.class, id));
  }

  @Test
  void classCrudAndPaginationUsePersistentRows() throws Exception {
    long id = schoolClass(teacher);
    assertEquals("一班", data(call("GET", "/classes/" + id, null, teacher)).path("name").asText());
    assertEquals(
        1, data(call("GET", "/classes?q=一班&page=1&size=10", null, teacher)).path("total").asInt());
    data(
        call(
            "PUT",
            "/classes/" + id,
            "{\"name\":\"二班\",\"grade\":\"高二\",\"student_count\":42}",
            teacher));
    assertEquals(
        "二班", jdbc.queryForObject("SELECT name FROM school_class WHERE id=?", String.class, id));
    data(call("DELETE", "/classes/" + id, null, teacher));
    assertEquals(404, call("GET", "/classes/" + id, null, teacher).statusCode());
  }

  @Test
  void tenantAndTeacherScopeCannotBeForged() throws Exception {
    long id = schoolClass(teacher);
    assertEquals(404, call("GET", "/classes/" + id, null, peer).statusCode());
    assertEquals(404, call("GET", "/classes/" + id, null, other).statusCode());
    assertEquals(0, data(call("GET", "/classes", null, other)).path("total").asInt());
    assertEquals(id, data(call("GET", "/classes/" + id, null, admin)).path("id").asLong());
    assertEquals(
        400,
        call(
                "POST",
                "/classes",
                "{\"name\":\"伪造\",\"grade\":\"一年级\",\"student_count\":3,\"tenant_id\":102}",
                teacher)
            .statusCode());
    assertEquals(
        403,
        call(
                "POST",
                "/classes",
                "{\"name\":\"代建\",\"grade\":\"一年级\",\"student_count\":3,\"owner_id\":1102}",
                teacher)
            .statusCode());
  }

  @Test
  void courseAndTaskInheritRelationshipsAndProtectReferencedRows() throws Exception {
    long classId = schoolClass(teacher);
    long courseId =
        data(call(
                "POST",
                "/courses",
                "{\"name\":\"数学\",\"class_id\":" + classId + ",\"term\":\"秋季\"}",
                teacher))
            .path("id")
            .asLong();
    long taskId =
        data(call("POST", "/tasks", "{\"name\":\"第一节课\",\"course_id\":" + courseId + "}", teacher))
            .path("id")
            .asLong();
    var task = data(call("GET", "/tasks/" + taskId, null, teacher));
    assertEquals(classId, task.path("class_id").asLong());
    assertEquals(1101, task.path("owner_id").asLong());
    assertEquals(409, call("DELETE", "/classes/" + classId, null, teacher).statusCode());
    assertEquals(409, call("DELETE", "/courses/" + courseId, null, teacher).statusCode());
    assertEquals(
        404,
        call(
                "POST",
                "/courses",
                "{\"name\":\"越权课程\",\"class_id\":" + classId + ",\"term\":\"秋季\"}",
                peer)
            .statusCode());
    data(call("DELETE", "/tasks/" + taskId, null, teacher));
    data(call("DELETE", "/courses/" + courseId, null, teacher));
    data(call("DELETE", "/classes/" + classId, null, teacher));
  }

  @Test
  void invalidAndDisabledIdentityAreRejected() throws Exception {
    assertEquals(
        400,
        call("POST", "/classes", "{\"name\":\"\",\"grade\":\"高一\",\"student_count\":-1}", teacher)
            .statusCode());
    assertEquals(401, call("GET", "/classes", null, null).statusCode());
    jdbc.update("UPDATE school_account SET enabled=false WHERE id=1101");
    assertTrue(
        java.util.Set.of(401, 403).contains(call("GET", "/classes", null, teacher).statusCode()));
  }
}
