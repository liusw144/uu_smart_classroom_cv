package cn.uusmart.classroom.api.media;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@SaCheckPermission("session:read")
public class MediaController {
  @GetMapping("/assets")
  public ApiResponse<Page<MediaAsset>> assets(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.assets(current.get(), q, page, size));
  }

  @DeleteMapping("/assets/{id}")
  public ApiResponse<Boolean> deleteAsset(@PathVariable UUID id) {
    service.deleteAsset(current.get(), id.toString());
    return new ApiResponse<>(true);
  }

  private final MediaService service;
  private final CurrentActor current;

  public MediaController(MediaService service, CurrentActor current) {
    this.service = service;
    this.current = current;
  }

  public record DetectionInput(
      @Positive long taskId,
      @NotNull UUID assetId,
      @Positive long modelId,
      @Pattern(regexp = "IMAGE|VIDEO|CAMERA") String mediaKind) {}

  public record ReportInput(@Positive long taskId, @NotBlank @Size(max = 128) String title) {}

  @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<MediaAsset> upload(
      @RequestParam MultipartFile file,
      @RequestParam(name = "task_id", required = false) Long taskId)
      throws IOException {
    var actor = current.get();
    if (file.getSize() > 32 * 1024 * 1024) throw ResourceProblem.invalid("媒体最多32MB");
    return new ApiResponse<>(
        service.uploadForTask(
            actor, taskId, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
  }

  @GetMapping("/assets/{id}")
  public ResponseEntity<byte[]> asset(@PathVariable UUID id) {
    var actor = current.get();
    var asset = service.asset(actor, id.toString());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(asset.contentType()))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(asset.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(service.content(actor, id.toString()));
  }

  @PostMapping("/detections")
  public ApiResponse<DetectionJob> submit(@Valid @RequestBody DetectionInput input) {
    return new ApiResponse<>(
        service.submit(
            current.get(),
            input.taskId(),
            input.assetId().toString(),
            input.modelId(),
            input.mediaKind()));
  }

  @GetMapping("/detections")
  public ApiResponse<Page<DetectionJob>> jobs(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.jobs(current.get(), q, status, page, size));
  }

  @GetMapping("/detections/{id}")
  public ApiResponse<DetectionJob> job(@PathVariable UUID id) {
    return new ApiResponse<>(service.job(current.get(), id.toString()));
  }

  @DeleteMapping("/detections/{id}")
  public ApiResponse<Boolean> deleteJob(@PathVariable UUID id) {
    service.deleteJob(current.get(), id.toString());
    return new ApiResponse<>(true);
  }

  @PostMapping("/reports")
  public ApiResponse<TeachingReport> createReport(@Valid @RequestBody ReportInput input) {
    return new ApiResponse<>(
        service.createReport(current.get(), input.taskId(), input.title().trim()));
  }

  @GetMapping("/reports")
  public ApiResponse<Page<TeachingReport>> reports(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.reports(current.get(), q, page, size));
  }

  @GetMapping("/reports/{id}")
  public ApiResponse<TeachingReport> report(@PathVariable UUID id) {
    return new ApiResponse<>(service.report(current.get(), id.toString()));
  }

  @DeleteMapping("/reports/{id}")
  public ApiResponse<Boolean> deleteReport(@PathVariable UUID id) {
    service.deleteReport(current.get(), id.toString());
    return new ApiResponse<>(true);
  }
}
