package cn.uusmart.classroom.api.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  @GetMapping("/health/live")
  public Map<String, String> live() {
    return Map.of("status", "alive", "version", "0.1.0");
  }
}
