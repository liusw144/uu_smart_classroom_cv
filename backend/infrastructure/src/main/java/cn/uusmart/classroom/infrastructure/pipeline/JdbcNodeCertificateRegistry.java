package cn.uusmart.classroom.infrastructure.pipeline;

import cn.uusmart.classroom.application.pipeline.NodeCertificateRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcNodeCertificateRegistry implements NodeCertificateRegistry {
  private final JdbcTemplate jdbc;

  public JdbcNodeCertificateRegistry(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean active(String node, String fingerprint) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM edge_node_certificate WHERE node_id=? AND fingerprint=? AND revoked_at IS NULL AND not_before<=CURRENT_TIMESTAMP(6) AND not_after>CURRENT_TIMESTAMP(6)",
            Integer.class,
            node,
            fingerprint);
    return count != null && count == 1;
  }
}
