package cn.uusmart.classroom.api.pipeline;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.*;
import cn.uusmart.classroom.domain.DetectionJob;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
@SaCheckPermission("session:read")
public class PipelineController {
  private final EdgeExecutionService service;
  private final MediaService media;
  private final CurrentActor current;
  private final QueuedJobService queue;
  private final LockedModels models;
  private final PipelineRepository repository;
  private final AnalysisService analysis;
  private final JsonMapper json = new JsonMapper();

  public PipelineController(
      EdgeExecutionService service,
      MediaService media,
      CurrentActor current,
      QueuedJobService queue,
      LockedModels models,
      PipelineRepository repository,
      AnalysisService analysis) {
    this.service = service;
    this.media = media;
    this.current = current;
    this.queue = queue;
    this.models = models;
    this.repository = repository;
    this.analysis = analysis;
  }

  @GetMapping("/capabilities")
  public ApiResponse<Map<String, Object>> capabilities() {
    var actor = current.get();
    TeachingService.requireSchoolUser(actor);
    return new ApiResponse<>(
        Map.of(
            "execution_mode",
            queue.enabled() ? "EDGE" : "UNAVAILABLE",
            "model_version",
            models.version(),
            "node_available",
            repository.availableNode(actor.tenantId()).isPresent(),
            "media_kinds",
            List.of("IMAGE", "VIDEO", "CAMERA"),
            "offline_scope",
            "已缓存且租约仍有效的作业"));
  }

  @GetMapping("/detections/{id}/result")
  public ApiResponse<JsonNode> result(@PathVariable UUID id) {
    return new ApiResponse<>(json.readTree(service.resultJson(current.get(), id.toString())));
  }

  @PostMapping("/detections/{id}/analysis")
  public ApiResponse<cn.uusmart.classroom.domain.TeachingReport> analyze(@PathVariable UUID id) {
    return new ApiResponse<>(analysis.analyze(current.get(), id.toString()));
  }

  @GetMapping("/detections/{id}/artifact")
  public ResponseEntity<byte[]> artifact(@PathVariable UUID id) {
    var actor = current.get();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(service.artifactType(actor, id.toString())))
        .header("X-Content-Type-Options", "nosniff")
        .body(service.artifact(actor, id.toString()));
  }

  @PostMapping("/detections/{id}/cancel")
  public ApiResponse<DetectionJob> cancel(@PathVariable UUID id) {
    var actor = current.get();
    service.cancel(actor, id.toString());
    return new ApiResponse<>(media.job(actor, id.toString()));
  }
}
