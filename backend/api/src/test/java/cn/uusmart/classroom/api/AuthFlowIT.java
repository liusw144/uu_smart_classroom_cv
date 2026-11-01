package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "classroom.bootstrap.enabled=false")
class AuthFlowIT {
  static {
    String url = System.getenv("DB_URL");
    if (url == null || !url.matches("^jdbc:mysql://[^/]+/classroom_test(?:\\?.*)?$")) {
      throw new IllegalStateException("集成测试只允许使用显式配置的 classroom_test 隔离数据库");
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BCryptPasswordEncoder passwords;
  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = new JsonMapper();

  @BeforeEach
  void seed() {
    jdbc.update("DELETE FROM school_account");
    jdbc.update("DELETE FROM tenant");
    jdbc.update(
        "INSERT INTO tenant(id,code,name) VALUES (1,'school-a','测试学校甲'),(2,'school-b','测试学校乙')");
    jdbc.update(
        "INSERT INTO school_account(id,tenant_id,username,password_hash,role) VALUES (1,1,'teacher',?,'TEACHER'),(2,2,'teacher',?,'TEACHER')",
        passwords.encode("Integration-only-Password-9821"),
        passwords.encode("Other-test-Password-9821"));
  }

  private HttpResponse<String> call(String method, String path, String body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return http.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String login() throws Exception {
    var response =
        call(
            "POST",
            "/api/v1/auth/login",
            "{\"school_code\":\"school-a\",\"username\":\"teacher\",\"password\":\"Integration-only-Password-9821\"}",
            null);
    assertEquals(200, response.statusCode(), response.body());
    var data = json.readTree(response.body()).path("data");
    assertEquals(1, data.path("user").path("tenant_id").asInt());
    return data.path("access_token").asText();
  }

  @Test
  void anonymousRequestIsRejected() throws Exception {
    assertEquals(401, call("GET", "/api/v1/auth/me", null, null).statusCode());
  }

  @Test
  void loginMeAndLogoutUseRealRedisSession() throws Exception {
    String token = login();
    var me = call("GET", "/api/v1/auth/me", null, token);
    assertEquals(200, me.statusCode(), me.body());
    assertEquals(1, json.readTree(me.body()).path("data").path("tenant_id").asInt());
    assertEquals(200, call("POST", "/api/v1/auth/logout", null, token).statusCode());
    assertEquals(401, call("GET", "/api/v1/auth/me", null, token).statusCode());
  }

  @Test
  void schoolCannotBeForgedThroughRequestBody() throws Exception {
    var wrongSchool =
        call(
            "POST",
            "/api/v1/auth/login",
            "{\"school_code\":\"school-b\",\"username\":\"teacher\",\"password\":\"Integration-only-Password-9821\"}",
            null);
    assertEquals(401, wrongSchool.statusCode());
    var forgedTenant =
        call(
            "POST",
            "/api/v1/auth/login",
            "{\"school_code\":\"school-a\",\"username\":\"teacher\",\"password\":\"Integration-only-Password-9821\",\"tenant_id\":2}",
            null);
    assertEquals(400, forgedTenant.statusCode());
  }

  @Test
  void disabledAccountCannotContinueUsingSession() throws Exception {
    String token = login();
    jdbc.update("UPDATE school_account SET enabled = 0 WHERE id = 1 AND tenant_id = 1");
    assertEquals(403, call("GET", "/api/v1/auth/me", null, token).statusCode());
  }
}
