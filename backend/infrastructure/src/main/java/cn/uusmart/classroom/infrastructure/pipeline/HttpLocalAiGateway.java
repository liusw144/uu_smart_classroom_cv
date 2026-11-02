package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.ServiceUnavailable;
import cn.uusmart.classroom.application.pipeline.LocalAiGateway;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpLocalAiGateway implements LocalAiGateway {
  private final URI endpoint;
  private final String token;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final JsonMapper json = new JsonMapper();

  public HttpLocalAiGateway(
      @Value("${classroom.ai.url:http://127.0.0.1:18188}") String url,
      @Value("${classroom.ai.token:}") String token) {
    URI base = URI.create(url);
    if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(base.getHost())
        || !"http".equals(base.getScheme())
        || base.getUserInfo() != null
        || base.getQuery() != null) throw new IllegalStateException("本机AI演示只允许回环地址");
    endpoint = base.resolve("/v1/chat/completions");
    this.token = token;
  }

  public String analyze(String facts) {
    if (token.length() < 32) throw new ServiceUnavailable("本地AI尚未配置");
    try {
      String body =
          json.writeValueAsString(
              Map.of(
                  "model",
                  "qwen3-local",
                  "temperature",
                  0,
                  "max_tokens",
                  384,
                  "chat_template_kwargs",
                  Map.of("enable_thinking", false),
                  "messages",
                  List.of(
                      Map.of(
                          "role",
                          "system",
                          "content",
                          "你是数据说明助手。仅根据提供的结构化统计，用中文写三句简短说明。不得推测人物身份、课堂专注度、教学质量。累计人次不是独立人数，候选姿态不代表课堂行为。数字必须与输入一致，不要编造趋势或准确率。"),
                      Map.of("role", "user", "content", facts))));
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(90))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      try (var input = response.body()) {
        bytes = input.readNBytes(65537);
      }
      if (response.statusCode() != 200 || bytes.length > 65536)
        throw new ServiceUnavailable("本地AI响应无效");
      var output = json.readTree(bytes);
      String content =
          output.path("choices").path(0).path("message").path("content").asText().trim();
      String finish = output.path("choices").path(0).path("finish_reason").asText();
      if (content.isBlank() || content.length() > 4000 || !finish.equals("stop"))
        throw new ServiceUnavailable("本地AI输出为空或被截断，请重试");
      return content;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailable("AI分析被中断");
    } catch (java.io.IOException | tools.jackson.core.JacksonException error) {
      throw new ServiceUnavailable("本地AI服务暂不可用");
    }
  }
}
