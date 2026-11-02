package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManagementFlowIT extends SchoolHttpITSupport {
  @Test
  void administratorManagesOnlySchoolAccounts() throws Exception {
    assertEquals(403, call("GET", "/users", null, teacher).statusCode());
    long id =
        data(call(
                "POST",
                "/users",
                "{\"username\":\"new-teacher\",\"password\":\"New-fixture-password-2026\",\"display_name\":\"新教师\",\"role\":\"TEACHER\"}",
                admin))
            .path("id")
            .asLong();
    var listed = data(call("GET", "/users?q=new-teacher", null, admin));
    assertEquals(1, listed.path("total").asInt());
    assertFalse(listed.toString().contains("password"));
    assertEquals(404, call("GET", "/users/2201", null, admin).statusCode());
    assertEquals(
        409,
        call(
                "PUT",
                "/users/1199",
                "{\"display_name\":\"管理员\",\"role\":\"SCHOOL_ADMIN\",\"enabled\":false}",
                admin)
            .statusCode());
    String token =
        data(call(
                "POST",
                "/auth/login",
                "{\"school_code\":\"mvp-a\",\"username\":\"new-teacher\",\"password\":\"New-fixture-password-2026\"}",
                null))
            .path("access_token")
            .asText();
    data(
        call(
            "PUT",
            "/users/" + id,
            "{\"display_name\":\"已停用\",\"role\":\"TEACHER\",\"enabled\":false}",
            admin));
    assertTrue(
        java.util.Set.of(401, 403).contains(call("GET", "/classes", null, token).statusCode()));
    assertEquals(
        400,
        call(
                "POST",
                "/users",
                "{\"username\":\"operator\",\"password\":\"New-fixture-password-2026\",\"display_name\":\"禁止\",\"role\":\"PLATFORM_OPERATOR\"}",
                admin)
            .statusCode());
  }

  @Test
  void profileAndPasswordChangeRequireOriginalPassword() throws Exception {
    var profile =
        data(
            call(
                "PUT",
                "/profile",
                "{\"display_name\":\"张老师\",\"email\":\"teacher@example.invalid\"}",
                teacher));
    assertEquals("张老师", profile.path("display_name").asText());
    assertEquals(
        401,
        call(
                "POST",
                "/profile/password",
                "{\"old_password\":\"incorrect-password\",\"new_password\":\"Changed-fixture-password-2026\"}",
                teacher)
            .statusCode());
    data(
        call(
            "POST",
            "/profile/password",
            "{\"old_password\":\"Audit-fixture-password-2026\",\"new_password\":\"Changed-fixture-password-2026\"}",
            teacher));
    assertEquals(401, call("GET", "/auth/me", null, teacher).statusCode());
    data(
        call(
            "POST",
            "/auth/login",
            "{\"school_code\":\"mvp-a\",\"username\":\"u1101\",\"password\":\"Changed-fixture-password-2026\"}",
            null));
  }

  @Test
  void noticesRespectPublicationAndTenantBoundary() throws Exception {
    long id =
        data(call(
                "POST",
                "/notices",
                "{\"title\":\"教学通知\",\"content\":\"请准备课堂资料\",\"published\":false}",
                admin))
            .path("id")
            .asLong();
    assertEquals(0, data(call("GET", "/notices", null, teacher)).path("total").asInt());
    assertEquals(404, call("GET", "/notices/" + id, null, teacher).statusCode());
    data(
        call(
            "PUT",
            "/notices/" + id,
            "{\"title\":\"教学通知\",\"content\":\"请准备课堂资料\",\"published\":true}",
            admin));
    assertEquals(1, data(call("GET", "/notices", null, teacher)).path("total").asInt());
    assertEquals(0, data(call("GET", "/notices", null, other)).path("total").asInt());
    assertEquals(403, call("DELETE", "/notices/" + id, null, teacher).statusCode());
    data(call("DELETE", "/notices/" + id, null, admin));
  }

  @Test
  void catalogsAreMetadataAndCanOnlyBeEditedByAdministrators() throws Exception {
    for (String endpoint : new String[] {"models", "categories"}) {
      String body =
          "{\"name\":\"验证档案\",\"code\":\"fixture-v1\",\"description\":\"档案不代表模型已加载\",\"enabled\":true}";
      assertEquals(403, call("POST", "/" + endpoint, body, teacher).statusCode());
      long id = data(call("POST", "/" + endpoint, body, admin)).path("id").asLong();
      assertEquals(1, data(call("GET", "/" + endpoint, null, teacher)).path("total").asInt());
      assertEquals(404, call("GET", "/" + endpoint + "/" + id, null, other).statusCode());
      assertEquals(409, call("POST", "/" + endpoint, body, admin).statusCode());
      data(call("PUT", "/" + endpoint + "/" + id, body.replace("true", "false"), admin));
      assertEquals(0, data(call("GET", "/" + endpoint, null, teacher)).path("total").asInt());
      data(call("DELETE", "/" + endpoint + "/" + id, null, admin));
    }
  }

  @Test
  void dashboardAndAuditReflectActualSchoolRequests() throws Exception {
    schoolClass(teacher);
    var own = data(call("GET", "/dashboard", null, teacher));
    assertEquals(1, own.path("class_count").asInt());
    assertEquals(0, own.path("course_count").asInt());
    assertEquals(0, data(call("GET", "/dashboard", null, other)).path("class_count").asInt());
    assertEquals(403, call("GET", "/logs", null, teacher).statusCode());
    var logs = data(call("GET", "/logs?q=classes", null, admin));
    assertTrue(logs.path("total").asInt() > 0);
    assertFalse(logs.toString().contains("Audit-fixture-password"));
    assertTrue(logs.path("items").toString().contains("/api/v1/classes"));
  }
}
