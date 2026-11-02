package cn.uusmart.classroom.api.pipeline;

import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.application.pipeline.*;
import cn.uusmart.classroom.domain.pipeline.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/internal/v1/edge")
public class EdgeNodeController {
  private final EdgeExecutionService service;

  public EdgeNodeController(EdgeExecutionService service) {
    this.service = service;
  }

  private NodePrincipal node(String id, String authorization) {
    return service.authenticate(id, authorization);
  }

  private String lease(String value) {
    try {
      return UUID.fromString(value).toString();
    } catch (RuntimeException error) {
      throw ResourceProblem.invalid("租约编号无效");
    }
  }

  @PostMapping("/claim")
  public ApiResponse<JobLease> claim(
      @RequestHeader(value = "X-Node-ID", required = false) String id,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return new ApiResponse<>(service.claim(node(id, authorization)));
  }

  @GetMapping("/jobs/{job}/asset")
  public ResponseEntity<byte[]> asset(
      @PathVariable UUID job,
      @RequestHeader(value = "X-Node-ID", required = false) String id,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Job-Lease", required = false) String lease) {
    var principal = node(id, authorization);
    String token = lease(lease);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(service.inputType(principal, job.toString(), token)))
        .header("X-Content-Type-Options", "nosniff")
        .body(service.input(principal, job.toString(), token));
  }

  @PostMapping(value = "/jobs/{job}/result", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Boolean> result(
      @PathVariable UUID job,
      @RequestHeader(value = "X-Node-ID", required = false) String id,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Job-Lease", required = false) String lease,
      @RequestPart("result") String result,
      @RequestPart("artifact") MultipartFile artifact)
      throws java.io.IOException {
    var principal = node(id, authorization);
    if (artifact.getSize() > 32 * 1024 * 1024) throw ResourceProblem.invalid("产物体积超出限制");
    return new ApiResponse<>(
        service.complete(principal, job.toString(), lease(lease), result, artifact.getBytes()));
  }

  public record Failure(@NotBlank String code) {}

  @PostMapping("/jobs/{job}/fail")
  public ApiResponse<Boolean> fail(
      @PathVariable UUID job,
      @RequestHeader(value = "X-Node-ID", required = false) String id,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Job-Lease", required = false) String lease,
      @Valid @RequestBody Failure failure) {
    service.fail(node(id, authorization), job.toString(), lease(lease), failure.code());
    return new ApiResponse<>(true);
  }

  @PostMapping("/jobs/{job}/heartbeat")
  public ApiResponse<Boolean> heartbeat(
      @PathVariable UUID job,
      @RequestHeader(value = "X-Node-ID", required = false) String id,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Job-Lease", required = false) String lease) {
    service.heartbeat(node(id, authorization), job.toString(), lease(lease));
    return new ApiResponse<>(true);
  }
}
