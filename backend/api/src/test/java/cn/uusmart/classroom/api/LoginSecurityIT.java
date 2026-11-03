package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "classroom.security.login.account-limit=2",
      "classroom.security.login.source-limit=8"
    })
class LoginSecurityIT extends SchoolHttpITSupport {
  @Test
  void forgedForwardedHeadersCannotResetSocketSourceBudget() throws Exception {
    int denied = 0;
    for (int i = 0; i < 10; i++) {
      var request =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
              .header("Content-Type", "application/json")
              .header("X-Forwarded-For", "198.51.100." + i)
              .POST(
                  java.net.http.HttpRequest.BodyPublishers.ofString(
                      "{\"school_code\":\"mvp-a\",\"username\":\"missing-"
                          + UUID.randomUUID()
                          + "\",\"password\":\"wrong-password\"}"))
              .build();
      int status =
          http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
      assertTrue(status == 401 || status == 429);
      if (status == 429) denied++;
    }
    assertEquals(6, denied);
  }

  @Test
  void redisAdmissionIsAtomicAcrossInstancesAndActualAccountAliases() {
    String namespace = "it-budget-" + UUID.randomUUID();
    var first =
        new cn.uusmart.classroom.api.auth.RedisLoginBudget(redis, namespace, 2, 20, 50, 1, 60, 5);
    var second =
        new cn.uusmart.classroom.api.auth.RedisLoginBudget(redis, namespace, 2, 20, 50, 1, 60, 5);
    try {
      try (var permit = first.acquire("school", "alias-a", "127.0.0.1")) {
        assertThrows(
            cn.uusmart.classroom.application.RequestThrottled.class,
            () -> second.acquire("school", "other", "127.0.0.2"));
        permit.identify(1101);
      }
      try (var permit = second.acquire("school", "alias-b", "127.0.0.2")) {
        permit.identify(1101);
      }
      try (var permit = first.acquire("school", "alias-c", "127.0.0.3")) {
        assertThrows(
            cn.uusmart.classroom.application.RequestThrottled.class, () -> permit.identify(1101));
      }
    } finally {
      var keys = redis.keys("{" + namespace + "}:*");
      if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
  }

  @Test
  void unavailableRedisFailsClosedAndDoesNotLeakLocalSlots() {
    var broken =
        org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    org.mockito.Mockito.when(
            broken.execute(
                org.mockito.ArgumentMatchers.any(
                    org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenThrow(new IllegalStateException("注入Redis故障"));
    var budget =
        new cn.uusmart.classroom.api.auth.RedisLoginBudget(
            broken, "test-unavailable", 2, 5, 10, 1, 60, 5);
    for (int i = 0; i < 3; i++)
      assertThrows(
          cn.uusmart.classroom.application.ServiceUnavailable.class,
          () -> budget.acquire("a", "b", "127.0.0.1"));
  }

  @Test
  void repeatedUnknownAccountIsThrottledBeforeUnlimitedPasswordChecks() throws Exception {
    String username = "missing-" + UUID.randomUUID();
    String input =
        "{\"school_code\":\"mvp-a\",\"username\":\""
            + username
            + "\",\"password\":\"wrong-password\"}";
    assertEquals(401, call("POST", "/auth/login", input, null).statusCode());
    assertEquals(401, call("POST", "/auth/login", input, null).statusCode());
    var rejected = call("POST", "/auth/login", input, null);
    assertEquals(429, rejected.statusCode());
    assertTrue(rejected.headers().firstValue("Retry-After").isPresent());
    assertEquals(
        "TOO_MANY_REQUESTS", json.readTree(rejected.body()).path("error").path("code").asText());
    assertEquals(101, data(call("GET", "/auth/me", null, teacher)).path("tenant_id").asInt());
  }
}
