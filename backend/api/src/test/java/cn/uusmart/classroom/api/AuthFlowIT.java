package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AuthFlowIT extends SchoolHttpITSupport {
  @Test
  void anonymousRequestIsRejected() throws Exception {
    assertEquals(401, call("GET", "/auth/me", null, null).statusCode());
  }

  @Test
  void loginMeAndLogoutUseRealRedisSession() throws Exception {
    assertEquals(101, data(call("GET", "/auth/me", null, teacher)).path("tenant_id").asInt());
    data(call("POST", "/auth/logout", null, teacher));
    assertEquals(401, call("GET", "/auth/me", null, teacher).statusCode());
  }

  @Test
  void schoolCannotBeForgedThroughRequestBody() throws Exception {
    assertEquals(
        401,
        call(
                "POST",
                "/auth/login",
                "{\"school_code\":\"mvp-b\",\"username\":\"u1101\",\"password\":\"Audit-fixture-password-2026\"}",
                null)
            .statusCode());
    assertEquals(
        400,
        call(
                "POST",
                "/auth/login",
                "{\"school_code\":\"mvp-a\",\"username\":\"u1101\",\"password\":\"Audit-fixture-password-2026\",\"tenant_id\":102}",
                null)
            .statusCode());
  }

  @Test
  void disabledAccountCannotContinueUsingSession() throws Exception {
    jdbc.update("UPDATE school_account SET enabled=false WHERE id=1101 AND tenant_id=101");
    assertEquals(403, call("GET", "/auth/me", null, teacher).statusCode());
  }
}
