package cn.uusmart.classroom.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "classroom.bootstrap.enabled=false",
      "classroom.pipeline.enabled=true",
      "classroom.models.lock=../../models/demo-assets.lock.json"
    })
class EdgeFlowIT extends SchoolHttpITSupport {
  static final String NODE_TOKEN = "edge-fixture-only-token-000000000000000000";

  String hash(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  void register() throws Exception {
    jdbc.update(
        "INSERT INTO edge_node(id,tenant_id,name,token_hash) VALUES ('it-node-a',101,'测试边缘节点',?),('it-node-b',102,'另一学校节点',?)",
        hash(NODE_TOKEN.getBytes(StandardCharsets.UTF_8)),
        hash((NODE_TOKEN + "other").getBytes(StandardCharsets.UTF_8)));
  }

  HttpResponse<String> node(
      String method, String path, String body, String token, String nodeId, String lease)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1/edge" + path))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (nodeId != null) builder.header("X-Node-ID", nodeId);
    if (lease != null) builder.header("X-Job-Lease", lease);
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

  String enqueue() throws Exception {
    long classId = schoolClass(teacher);
    long courseId =
        data(call(
                "POST",
                "/courses",
                "{\"name\":\"边缘课程\",\"class_id\":" + classId + ",\"term\":\"秋季\"}",
                teacher))
            .path("id")
            .asLong();
    long taskId =
        data(call("POST", "/tasks", "{\"name\":\"边缘课堂\",\"course_id\":" + courseId + "}", teacher))
            .path("id")
            .asLong();
    var file = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        file);
    String boundary = "edge-upload-test";
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\nContent-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    bytes.write(file.toByteArray());
    bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var upload =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/assets"))
            .header("Authorization", "Bearer " + teacher)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
            .build();
    String asset =
        data(http.send(upload, HttpResponse.BodyHandlers.ofString())).path("id").asText();
    long modelId =
        data(call(
                "POST",
                "/models",
                "{\"name\":\"已锁定模型\",\"code\":\"yolox-tiny-rtmpose-s-v1\",\"description\":\"只用于协议测试，不代表本测试执行模型\",\"enabled\":true}",
                admin))
            .path("id")
            .asLong();
    var job =
        data(
            call(
                "POST",
                "/detections",
                "{\"task_id\":"
                    + taskId
                    + ",\"asset_id\":\""
                    + asset
                    + "\",\"model_id\":"
                    + modelId
                    + "}",
                teacher));
    assertEquals("QUEUED", job.path("status").asText());
    return job.path("id").asText();
  }

  @Test
  void anonymousAndHumanSessionCannotClaimNodeJobs() throws Exception {
    assertEquals(401, node("POST", "/claim", "{}", null, null, null).statusCode());
    assertEquals(401, node("POST", "/claim", "{}", teacher, "it-node-a", null).statusCode());
  }

  @Test
  void claimIsScopedAndLeaseCannotBeForged() throws Exception {
    register();
    String job = enqueue();
    assertTrue(
        data(node("POST", "/claim", "{}", NODE_TOKEN + "other", "it-node-b", null)).isNull());
    var claimed = data(node("POST", "/claim", "{}", NODE_TOKEN, "it-node-a", null));
    assertEquals(job, claimed.path("job_id").asText());
    String lease = claimed.path("lease_id").asText();
    assertFalse(lease.isBlank());
    assertEquals(1, claimed.path("attempt").asInt());
    assertEquals(
        409,
        node(
                "GET",
                "/jobs/" + job + "/asset",
                null,
                NODE_TOKEN,
                "it-node-a",
                "00000000-0000-0000-0000-000000000000")
            .statusCode());
    assertEquals(
        200,
        node("GET", "/jobs/" + job + "/asset", null, NODE_TOKEN, "it-node-a", lease).statusCode());
    assertEquals(
        404,
        node("GET", "/jobs/" + job + "/asset", null, NODE_TOKEN + "other", "it-node-b", lease)
            .statusCode());
    var repeat = data(node("POST", "/claim", "{}", NODE_TOKEN, "it-node-a", null));
    assertEquals(lease, repeat.path("lease_id").asText());
    jdbc.update(
        "UPDATE detection_job SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
        job);
    var retry = data(node("POST", "/claim", "{}", NODE_TOKEN, "it-node-a", null));
    assertEquals(2, retry.path("attempt").asInt());
    assertNotEquals(lease, retry.path("lease_id").asText());
    assertEquals(
        409,
        node("GET", "/jobs/" + job + "/asset", null, NODE_TOKEN, "it-node-a", lease).statusCode());
  }

  @Test
  void cancellationRevokesLeaseAndResultAccessIsScoped() throws Exception {
    register();
    String job = enqueue();
    var claimed = data(node("POST", "/claim", "{}", NODE_TOKEN, "it-node-a", null));
    assertEquals(404, call("POST", "/detections/" + job + "/cancel", null, other).statusCode());
    assertEquals(
        "CANCELLED",
        data(call("POST", "/detections/" + job + "/cancel", null, teacher))
            .path("status")
            .asText());
    assertEquals(
        409,
        node(
                "GET",
                "/jobs/" + job + "/asset",
                null,
                NODE_TOKEN,
                "it-node-a",
                claimed.path("lease_id").asText())
            .statusCode());
    assertEquals(404, call("GET", "/detections/" + job + "/result", null, other).statusCode());
    assertEquals(409, call("GET", "/detections/" + job + "/result", null, teacher).statusCode());
    assertEquals(409, call("POST", "/detections/" + job + "/analysis", null, teacher).statusCode());
    assertEquals(404, call("POST", "/detections/" + job + "/analysis", null, other).statusCode());
  }

  HttpResponse<String> callback(
      String id, String lease, String result, byte[] artifact, String token, String nodeId)
      throws Exception {
    String boundary = "edge-result-boundary";
    var body = new java.io.ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"result\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                + result
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"artifact\"; filename=\"artifact.png\"\r\nContent-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(artifact);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/internal/v1/edge/jobs/" + id + "/result"))
            .header("Authorization", "Bearer " + token)
            .header("X-Node-ID", nodeId)
            .header("X-Job-Lease", lease)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void completionChecksProofAndIsIdempotentWithoutAcceptingTamperedReplay() throws Exception {
    register();
    String id = enqueue();
    var lease = data(node("POST", "/claim", "{}", NODE_TOKEN, "it-node-a", null));
    String leaseId = lease.path("lease_id").asText();
    byte[] artifact = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};
    var value = json.createObjectNode();
    value.put("schema_version", 1);
    value.put("job_id", id);
    value.put("asset_id", lease.path("asset_id").asText());
    value.put("input_sha256", lease.path("input_sha256").asText());
    value.put("model_version", lease.path("model_version").asText());
    value.put("media_kind", "IMAGE");
    value.put("width", 2);
    value.put("height", 2);
    value.put("duration_ms", 0);
    value.put("sample_interval_ms", 250);
    value.put("sampled_frames", 1);
    value.put("person_instances", 0);
    value.putNull("unique_people");
    value.putObject("pose_counts").put("HAND_UP_CANDIDATE", 0);
    value.putArray("frames").addObject().put("index", 0).put("timestamp_ms", 0).putArray("persons");
    value.set(
        "model_hashes",
        json.readTree(
                        java.nio.file.Files.readString(
                            java.nio.file.Path.of("../../models/demo-assets.lock.json")))
                    .get("detector")
                == null
            ? json.createObjectNode()
            : json.createObjectNode()
                .put("detector", "427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7")
                .put("pose", "9aeb635b83f86aea45cf45d85798f7eba1a162de8e0d721c44e54fe5eebaf47d"));
    value.put("artifact_sha256", hash(artifact));
    value.put("artifact_size", artifact.length);
    value.put("artifact_content_type", "image/png");
    value.put("request_digest", "a".repeat(64));
    value.put("processed_at", "2026-11-02T00:00:00Z");
    value.put("elapsed_ms", 1);
    value.put("interpretation", "合成协议测试，不是模型推理证据");
    String result = json.writeValueAsString(value);
    assertEquals(
        404,
        callback(id, leaseId, result, artifact, NODE_TOKEN + "other", "it-node-b").statusCode());
    value.remove("duration_ms");
    assertEquals(
        400,
        callback(id, leaseId, json.writeValueAsString(value), artifact, NODE_TOKEN, "it-node-a")
            .statusCode());
    value.put("duration_ms", 0);
    value.put("person_instances", 1);
    assertEquals(
        400,
        callback(id, leaseId, json.writeValueAsString(value), artifact, NODE_TOKEN, "it-node-a")
            .statusCode());
    assertEquals(
        200, callback(id, leaseId, result, artifact, NODE_TOKEN, "it-node-a").statusCode());
    assertEquals(
        200, callback(id, leaseId, result, artifact, NODE_TOKEN, "it-node-a").statusCode());
    artifact[8] = 1;
    assertEquals(
        400, callback(id, leaseId, result, artifact, NODE_TOKEN, "it-node-a").statusCode());
    assertEquals(404, call("GET", "/detections/" + id + "/result", null, peer).statusCode());
    assertEquals(404, call("GET", "/detections/" + id + "/artifact", null, other).statusCode());
    assertEquals(200, call("GET", "/detections/" + id + "/result", null, teacher).statusCode());
    assertEquals(
        "SUCCEEDED", data(call("GET", "/detections/" + id, null, teacher)).path("status").asText());
  }
}
