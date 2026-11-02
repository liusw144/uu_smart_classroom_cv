package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.pipeline.LockedModels;
import java.nio.file.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonLockedModels implements LockedModels {
  private final String version;
  private final Map<String, String> hashes;
  private final String aiHash;

  public JsonLockedModels(
      @Value("${classroom.models.lock:classpath:models/demo-assets.lock.json}") String path) {
    try {
      String text;
      if (path.startsWith("classpath:")) {
        try (var input =
            getClass().getResourceAsStream("/" + path.substring("classpath:".length()))) {
          if (input == null) throw new java.io.IOException("模型清单资源缺失");
          text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
      } else text = Files.readString(Path.of(path));
      var data = new JsonMapper().readTree(text);
      version = data.path("model_version").asText();
      hashes =
          Map.of(
              "detector",
              data.path("detector").path("sha256").asText(),
              "pose",
              data.path("pose").path("sha256").asText());
      aiHash = data.path("ai").path("sha256").asText();
      if (version.isBlank()
          || hashes.values().stream().anyMatch(value -> !value.matches("[a-f0-9]{64}"))
          || !aiHash.matches("[a-f0-9]{64}")) throw new IllegalStateException("模型清单格式无效");
    } catch (java.io.IOException error) {
      throw new IllegalStateException("模型清单不可读取", error);
    }
  }

  public String version() {
    return version;
  }

  public Map<String, String> hashes() {
    return hashes;
  }

  public String aiHash() {
    return aiHash;
  }
}
