package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "classroom.bootstrap.enabled=false")
abstract class SchoolHttpITSupport {
  private boolean ownsFixtures;

  static {
    if (!String.valueOf(System.getenv("DB_URL"))
        .matches("^jdbc:mysql://[^/]+/uu_classroom_test(?:\\?.*)?$")) {
      throw new IllegalStateException("本机业务测试只允许uu_classroom_test专用库");
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BCryptPasswordEncoder passwords;
  final HttpClient http = HttpClient.newHttpClient();
  final JsonMapper json = new JsonMapper();
  String teacher;
  String peer;
  String other;
  String admin;

  @BeforeEach
  void seed() throws Exception {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE id IN (101,102)", Integer.class) > 0)
      throw new IllegalStateException("测试学校编号已被占用；保留已有数据，不执行清理或覆盖");
    jdbc.update(
        "INSERT INTO tenant(id,code,name) VALUES (101,'mvp-a','验证学校甲'),(102,'mvp-b','验证学校乙')");
    ownsFixtures = true;
    for (long id : new long[] {1101, 1102, 1199, 2201}) {
      jdbc.update(
          "INSERT INTO school_account(id,tenant_id,username,password_hash,role) VALUES (?,?,?,?,?)",
          id,
          id == 2201 ? 102 : 101,
          "u" + id,
          passwords.encode("Audit-fixture-password-2026"),
          id == 1199 ? "SCHOOL_ADMIN" : "TEACHER");
    }
    teacher = login("mvp-a", "u1101");
    peer = login("mvp-a", "u1102");
    other = login("mvp-b", "u2201");
    admin = login("mvp-a", "u1199");
  }

  @AfterEach
  void cleanup() {
    if (!ownsFixtures) return;
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='school_account' AND COLUMN_NAME='avatar_asset_id'",
            Integer.class)
        > 0)
      jdbc.update("UPDATE school_account SET avatar_asset_id=NULL WHERE tenant_id IN (101,102)");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='media_asset'",
            Integer.class)
        > 0) {
      jdbc.query(
          "SELECT id,tenant_id FROM media_asset WHERE tenant_id IN (101,102)",
          (org.springframework.jdbc.core.RowCallbackHandler)
              row -> {
                java.nio.file.Path root =
                    java.nio.file.Path.of(System.getenv("CLASSROOM_ASSETS_ROOT"))
                        .toAbsolutePath()
                        .normalize();
                java.nio.file.Path file =
                    root.resolve(Long.toString(row.getLong("tenant_id")))
                        .resolve(java.util.UUID.fromString(row.getString("id")).toString())
                        .normalize();
                if (!file.startsWith(root)) throw new IllegalStateException("测试素材清理越界");
                try {
                  java.nio.file.Files.deleteIfExists(file);
                } catch (java.io.IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    for (String table :
        new String[] {
          "analysis_report",
          "detection_job",
          "media_asset",
          "operation_log",
          "edge_node",
          "lesson_task",
          "course",
          "school_class",
          "school_notice",
          "model_catalog",
          "behavior_category"
        }) {
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
              Integer.class,
              table)
          > 0) jdbc.update("DELETE FROM " + table + " WHERE tenant_id IN (101,102)");
    }
    jdbc.update("DELETE FROM school_account WHERE tenant_id IN (101,102)");
    jdbc.update("DELETE FROM tenant WHERE id IN (101,102)");
    ownsFixtures = false;
  }

  HttpResponse<String> call(String method, String path, String body, String token)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .header("Content-Type", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode data(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body()).path("data");
  }

  String login(String school, String user) throws Exception {
    return data(call(
            "POST",
            "/auth/login",
            "{\"school_code\":\""
                + school
                + "\",\"username\":\""
                + user
                + "\",\"password\":\"Audit-fixture-password-2026\"}",
            null))
        .path("access_token")
        .asText();
  }

  long schoolClass(String token) throws Exception {
    return data(call(
            "POST", "/classes", "{\"name\":\"一班\",\"grade\":\"高一\",\"student_count\":40}", token))
        .path("id")
        .asLong();
  }
}
