package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.VisionGateway;
import cn.uusmart.classroom.domain.MediaAsset;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpVisionGateway implements VisionGateway {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String base;
  private final String token;

  public HttpVisionGateway(
      @Value("${classroom.vision.url:http://127.0.0.1:5000}") String base,
      @Value("${classroom.vision.token:}") String token) {
    this.base = base;
    this.token = token;
  }

  public String unavailableReason(String jobId, MediaAsset asset, String version) {
    if (token.length() < 32) return "VISION_UNAVAILABLE";
    String body =
        "{\"job_id\":\""
            + jobId
            + "\",\"asset_id\":\""
            + asset.id()
            + "\",\"input_sha256\":\""
            + asset.sha256()
            + "\",\"model_version\":\""
            + version
            + "\"}";
    var request =
        HttpRequest.newBuilder(URI.create(base + "/internal/v1/detections"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 503 && response.body().contains("MODEL_UNAVAILABLE"))
        return "MODEL_UNAVAILABLE";
      // 成功结果的持久化协议尚未交付，不能把未知响应转换成成功检测。
      return response.statusCode() == 200 ? "VISION_RESULT_UNSUPPORTED" : "VISION_UNAVAILABLE";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "VISION_UNAVAILABLE";
    } catch (java.io.IOException error) {
      return "VISION_UNAVAILABLE";
    }
  }
}
