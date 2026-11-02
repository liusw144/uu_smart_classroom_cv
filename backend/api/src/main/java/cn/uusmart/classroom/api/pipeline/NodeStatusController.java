package cn.uusmart.classroom.api.pipeline;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.api.web.ApiResponse;
import cn.uusmart.classroom.application.Page;
import cn.uusmart.classroom.application.pipeline.NodeStatusService;
import cn.uusmart.classroom.domain.pipeline.EdgeNodeStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/edge-nodes")
@SaCheckPermission("session:read")
public class NodeStatusController {
  private final NodeStatusService service;
  private final CurrentActor current;

  public NodeStatusController(NodeStatusService service, CurrentActor current) {
    this.service = service;
    this.current = current;
  }

  @GetMapping
  public ApiResponse<Page<EdgeNodeStatus>> list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new ApiResponse<>(service.list(current.get(), q, page, size));
  }
}
