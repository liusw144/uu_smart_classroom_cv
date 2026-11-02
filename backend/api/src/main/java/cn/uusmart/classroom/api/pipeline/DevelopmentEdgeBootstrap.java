package cn.uusmart.classroom.api.pipeline;

import cn.uusmart.classroom.application.pipeline.EdgeExecutionService;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(100)
@ConditionalOnProperty(name = "classroom.pipeline.enabled", havingValue = "true")
public class DevelopmentEdgeBootstrap implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final Environment environment;

  @Value("${classroom.edge.token:}")
  private String token;

  @Value("${classroom.edge.node-id:local-edge-01}")
  private String node;

  public DevelopmentEdgeBootstrap(JdbcTemplate jdbc, Environment environment) {
    this.jdbc = jdbc;
    this.environment = environment;
  }

  public void run(ApplicationArguments args) {
    if (!environment.acceptsProfiles(Profiles.of("dev"))
        || environment.acceptsProfiles(Profiles.of("prod"))) return;
    if (token.length() < 32 || !node.matches("[A-Za-z0-9_-]{3,64}"))
      throw new IllegalStateException("开发节点配置无效");
    Long tenant =
        jdbc.queryForObject("SELECT id FROM tenant WHERE code='local-school'", Long.class);
    String hash = EdgeExecutionService.hash(token.getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "INSERT IGNORE INTO edge_node(id,tenant_id,name,token_hash) VALUES (?,?,'本机边缘节点',?)",
        node,
        tenant,
        hash);
    Integer matching =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM edge_node WHERE id=? AND tenant_id=? AND token_hash=?",
            Integer.class,
            node,
            tenant,
            hash);
    if (matching == null || matching != 1)
      throw new IllegalStateException("已有节点与当前学校或凭据不匹配，拒绝自动覆盖");
  }
}
