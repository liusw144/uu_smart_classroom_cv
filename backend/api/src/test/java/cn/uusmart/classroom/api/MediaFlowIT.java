package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;

class MediaFlowIT extends SchoolHttpITSupport {
  @Test
  void assetListOnlyIncludesCurrentScope() throws Exception {
    asset(teacher);
    assertEquals(1, data(call("GET", "/assets", null, teacher)).path("total").asInt());
    assertEquals(0, data(call("GET", "/assets", null, peer)).path("total").asInt());
    assertEquals(0, data(call("GET", "/assets", null, other)).path("total").asInt());
  }

  @Test
  void administratorUploadForTeacherTaskDerivesOwnerOnServer() throws Exception {
    long classId = schoolClass(teacher);
    long courseId =
        data(call(
                "POST",
                "/courses",
                "{\"name\":\"代提交验证\",\"class_id\":" + classId + ",\"term\":\"秋季\"}",
                teacher))
            .path("id")
            .asLong();
    long taskId =
        data(call("POST", "/tasks", "{\"name\":\"代提交课堂\",\"course_id\":" + courseId + "}", teacher))
            .path("id")
            .asLong();
    var uploaded = data(upload(png(), "admin.png", "image/png", admin, taskId));
    assertEquals(1101, uploaded.path("owner_id").asLong());
    assertEquals(
        200, call("GET", "/assets/" + uploaded.path("id").asText(), null, teacher).statusCode());
    assertEquals(404, upload(png(), "other.png", "image/png", other, taskId).statusCode());
  }

  @Test
  void malformedIdentifiersAndPaginationReturnClientErrors() throws Exception {
    assertEquals(400, call("GET", "/assets/not-a-uuid", null, teacher).statusCode());
    assertEquals(400, call("GET", "/classes?page=not-a-number", null, teacher).statusCode());
    assertEquals(400, call("GET", "/classes?page=0", null, teacher).statusCode());
  }

  @Test
  void avatarReferenceProtectsAssetUntilCleared() throws Exception {
    String id = asset(teacher);
    data(
        call(
            "PUT",
            "/profile",
            "{\"display_name\":\"头像验证\",\"email\":\"\",\"avatar_asset_id\":\"" + id + "\"}",
            teacher));
    assertEquals(409, call("DELETE", "/assets/" + id, null, teacher).statusCode());
    data(
        call(
            "PUT",
            "/profile",
            "{\"display_name\":\"头像验证\",\"email\":\"\",\"avatar_asset_id\":null}",
            teacher));
    data(call("DELETE", "/assets/" + id, null, teacher));
    assertEquals(404, call("GET", "/assets/" + id, null, teacher).statusCode());
  }

  HttpResponse<String> upload(byte[] file, String name, String type, String token)
      throws Exception {
    return upload(file, name, type, token, null);
  }

  HttpResponse<String> upload(byte[] file, String name, String type, String token, Long taskId)
      throws Exception {
    String boundary = "uu-classroom-audit-boundary";
    byte[] prefix =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + name
                + "\"\r\nContent-Type: "
                + type
                + "\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var buffer = new java.io.ByteArrayOutputStream();
    buffer.write(prefix);
    buffer.write(file);
    buffer.write(
        ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/assets"
                        + (taskId == null ? "" : "?task_id=" + taskId)))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  byte[] png() throws Exception {
    var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var output = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  String asset(String token) throws Exception {
    return data(upload(png(), "audit.png", "image/png", token)).path("id").asText();
  }

  @Test
  void uploadedImageIsScopedAndFakeImagesAreRejected() throws Exception {
    String id = asset(teacher);
    assertEquals(200, call("GET", "/assets/" + id, null, teacher).statusCode());
    assertEquals(404, call("GET", "/assets/" + id, null, peer).statusCode());
    assertEquals(404, call("GET", "/assets/" + id, null, other).statusCode());
    assertEquals(
        400,
        upload("<script>invalid</script>".getBytes(), "fake.png", "image/png", teacher)
            .statusCode());
    assertEquals(400, upload(png(), "audit.svg", "image/svg+xml", teacher).statusCode());
    var profile =
        data(
            call(
                "PUT",
                "/profile",
                "{\"display_name\":\"图片教师\",\"email\":\"\",\"avatar_asset_id\":\"" + id + "\"}",
                teacher));
    assertEquals(id, profile.path("avatar_asset_id").asText());
    assertEquals(
        404,
        call(
                "PUT",
                "/profile",
                "{\"display_name\":\"不得借用\",\"email\":\"\",\"avatar_asset_id\":\"" + id + "\"}",
                peer)
            .statusCode());
  }

  @Test
  void unavailableModelCreatesExplicitFailedJobAndProtectsReferences() throws Exception {
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
        data(call("POST", "/tasks", "{\"name\":\"课堂\",\"course_id\":" + courseId + "}", teacher))
            .path("id")
            .asLong();
    long modelId =
        data(call(
                "POST",
                "/models",
                "{\"name\":\"待接入模型\",\"code\":\"fixture-v1\",\"description\":\"仅为档案\",\"enabled\":true}",
                admin))
            .path("id")
            .asLong();
    String asset = asset(teacher);
    String input =
        "{\"task_id\":" + taskId + ",\"asset_id\":\"" + asset + "\",\"model_id\":" + modelId + "}";
    var job = data(call("POST", "/detections", input, teacher));
    assertEquals("FAILED", job.path("status").asText());
    assertTrue(
        java.util.Set.of("MODEL_UNAVAILABLE", "VISION_UNAVAILABLE")
            .contains(job.path("error_code").asText()));
    assertEquals(404, call("POST", "/detections", input, peer).statusCode());
    assertEquals(
        404, call("GET", "/detections/" + job.path("id").asText(), null, other).statusCode());
    assertEquals(409, call("DELETE", "/tasks/" + taskId, null, teacher).statusCode());
    assertEquals(409, call("DELETE", "/models/" + modelId, null, admin).statusCode());
    var report =
        data(
            call("POST", "/reports", "{\"task_id\":" + taskId + ",\"title\":\"课堂教务汇总\"}", teacher));
    assertEquals("LOCAL_TEACHING_TEMPLATE", report.path("source").asText());
    assertTrue(report.path("content").asText().contains("不包含课堂行为判断"));
    assertEquals(
        404, call("GET", "/reports/" + report.path("id").asText(), null, other).statusCode());
    assertEquals(
        1, data(call("GET", "/detections?status=FAILED", null, teacher)).path("total").asInt());
    assertEquals(
        0, data(call("GET", "/detections?status=SUCCEEDED", null, teacher)).path("total").asInt());
  }
}
