package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.*;
import cn.uusmart.classroom.domain.pipeline.JobLease;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonInferenceResultCodec implements InferenceResultCodec {
  private final JsonMapper json = new JsonMapper();
  private final LockedModels models;

  public JsonInferenceResultCodec(LockedModels models) {
    this.models = models;
  }

  private void require(boolean condition) {
    if (!condition) throw ResourceProblem.invalid("推理结果与作业、模型或统计明细不一致");
  }

  public Proof validate(String value, JobLease lease, byte[] artifact) {
    JsonNode result;
    try {
      result = json.readTree(value);
    } catch (RuntimeException error) {
      throw ResourceProblem.invalid("结果JSON无效");
    }
    require(result.isObject() && Set.of(1, 2).contains(result.path("schema_version").asInt()));
    var fields =
        new ArrayList<>(
            List.of(
                "schema_version",
                "job_id",
                "asset_id",
                "input_sha256",
                "model_version",
                "model_hashes",
                "media_kind",
                "width",
                "height",
                "duration_ms",
                "sample_interval_ms",
                "sampled_frames",
                "person_instances",
                "unique_people",
                "pose_counts",
                "frames",
                "artifact_sha256",
                "artifact_size",
                "artifact_content_type",
                "request_digest",
                "processed_at",
                "elapsed_ms",
                "interpretation"));
    if (result.path("schema_version").asInt() == 2) {
      fields.addAll(List.of("sampling_strategy", "coverage_start_ms", "coverage_end_ms"));
      require(
          Set.of("STILL", "FIXED_INTERVAL_WITH_TAIL", "UNIFORM_FULL_SPAN")
              .contains(result.path("sampling_strategy").asText()));
      require(
          result.path("coverage_start_ms").isIntegralNumber()
              && result.path("coverage_end_ms").isIntegralNumber());
      require(
          result.path("coverage_start_ms").asInt()
              == result.path("frames").path(0).path("timestamp_ms").asInt());
      require(
          result.path("coverage_end_ms").asInt()
              == result
                  .path("frames")
                  .path(result.path("frames").size() - 1)
                  .path("timestamp_ms")
                  .asInt());
      require(result.path("coverage_end_ms").asInt() <= result.path("duration_ms").asInt());
    }
    require(result.size() == fields.size());
    for (String field : fields) require(result.has(field));
    for (String field :
        List.of(
            "schema_version",
            "width",
            "height",
            "duration_ms",
            "sample_interval_ms",
            "sampled_frames",
            "person_instances",
            "artifact_size",
            "elapsed_ms")) require(result.path(field).isIntegralNumber());
    require(
        result.path("sample_interval_ms").asInt() >= 100
            && result.path("sample_interval_ms").asInt() <= 30000
            && result.path("elapsed_ms").asLong() >= 0);
    require(result.path("request_digest").asText().matches("[a-f0-9]{64}"));
    try {
      java.time.Instant.parse(result.path("processed_at").asText());
    } catch (java.time.format.DateTimeParseException error) {
      throw ResourceProblem.invalid("推理完成时间无效");
    }
    require(
        result.path("job_id").asText().equals(lease.jobId())
            && result.path("asset_id").asText().equals(lease.assetId())
            && result.path("input_sha256").asText().equals(lease.inputSha256())
            && result.path("model_version").asText().equals(lease.modelVersion())
            && result.path("media_kind").asText().equals(lease.mediaKind()));
    for (var model : models.hashes().entrySet())
      require(model.getValue().equals(result.path("model_hashes").path(model.getKey()).asText()));
    int width = result.path("width").asInt(), height = result.path("height").asInt();
    require(width >= 2 && width <= 1280 && height >= 2 && height <= 1280);
    require(
        result.path("duration_ms").asLong() >= 0
            && result.path("duration_ms").asLong() <= 30000
            && result.path("unique_people").isNull());
    var frames = result.path("frames");
    require(
        frames.isArray()
            && frames.size() > 0
            && frames.size() <= 32
            && frames.size() == result.path("sampled_frames").asInt());
    int people = 0, raised = 0;
    for (var frame : frames) {
      require(
          frame.path("index").isIntegralNumber()
              && frame.path("index").asInt() >= 0
              && frame.path("timestamp_ms").isIntegralNumber()
              && frame.path("timestamp_ms").asInt() >= 0
              && frame.path("timestamp_ms").asInt() <= result.path("duration_ms").asLong());
      var persons = frame.path("persons");
      require(persons.isArray() && persons.size() <= 16);
      for (var person : persons) {
        var box = person.path("bbox");
        require(box.isArray() && box.size() == 4);
        for (var coordinate : box)
          require(
              coordinate.isNumber()
                  && Double.isFinite(coordinate.asDouble())
                  && Math.abs(coordinate.asDouble()) <= Math.max(width, height) * 4);
        var points = person.path("keypoints");
        require(points.isArray() && points.size() == 17);
        for (var point : points) {
          require(point.isArray() && point.size() == 3);
          for (var coordinate : point)
            require(
                coordinate.isNumber()
                    && Double.isFinite(coordinate.asDouble())
                    && Math.abs(coordinate.asDouble()) <= Math.max(width, height) * 4);
        }
        var candidates = person.path("pose_candidates");
        require(candidates.isArray() && candidates.size() <= 1);
        for (var candidate : candidates) {
          require(candidate.asText().equals("HAND_UP_CANDIDATE"));
          raised++;
        }
      }
      people += persons.size();
    }
    require(
        people == result.path("person_instances").asInt()
            && raised == result.path("pose_counts").path("HAND_UP_CANDIDATE").asInt());
    String type = result.path("artifact_content_type").asText();
    require(type.equals(lease.mediaKind().equals("VIDEO") ? "video/mp4" : "image/png"));
    require(
        result.path("artifact_size").asLong() == artifact.length
            && result.path("artifact_sha256").asText().equals(EdgeExecutionService.hash(artifact)));
    require(
        type.equals("image/png")
            ? artifact.length > 8
                && artifact[0] == (byte) 0x89
                && artifact[1] == 'P'
                && artifact[2] == 'N'
                && artifact[3] == 'G'
            : artifact.length > 12
                && artifact[4] == 'f'
                && artifact[5] == 't'
                && artifact[6] == 'y'
                && artifact[7] == 'p');
    return new Proof(type, result.path("artifact_sha256").asText());
  }

  public String summary(String value) {
    var result = json.readTree(value);
    return json.writeValueAsString(
        Map.of(
            "model_version",
            result.path("model_version").asText(),
            "sampled_frames",
            result.path("sampled_frames").asInt(),
            "person_instances",
            result.path("person_instances").asInt(),
            "hand_up_candidates",
            result.path("pose_counts").path("HAND_UP_CANDIDATE").asInt(),
            "interpretation",
            "通用人物与姿态样例；累计人次不是独立人数；没有课堂行为准确率证据"));
  }
}
