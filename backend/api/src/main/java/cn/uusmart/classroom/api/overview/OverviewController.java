package cn.uusmart.classroom.api.overview;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.OperationLog;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@SaCheckPermission("session:read")
public class OverviewController {
  private final SchoolOverviewService service;
  private final CurrentActor current;

  public OverviewController(SchoolOverviewService service, CurrentActor current) {
    this.service = service;
    this.current = current;
  }

  @GetMapping("/dashboard")
  public ApiResponse<Map<String, Long>> dashboard() {
    return new ApiResponse<>(service.dashboard(current.get()));
  }

  @GetMapping("/logs")
  public ApiResponse<Page<OperationLog>> logs(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.logs(current.get(), q, page, size));
  }
}
