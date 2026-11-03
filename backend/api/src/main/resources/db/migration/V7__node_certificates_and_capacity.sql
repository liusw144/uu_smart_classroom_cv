-- 证书由离线CA签发；绑定与吊销属于受审计的运维操作，不接受节点自行注册。
CREATE TABLE edge_node_certificate (
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  node_id VARCHAR(64) NOT NULL,
  not_before TIMESTAMP(6) NOT NULL,
  not_after TIMESTAMP(6) NOT NULL,
  revoked_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX ix_node_certificate (node_id,revoked_at),
  FOREIGN KEY (node_id) REFERENCES edge_node(id),
  CONSTRAINT ck_node_certificate_dates CHECK (not_after>not_before)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

ALTER TABLE edge_node ADD COLUMN max_inflight INT NOT NULL DEFAULT 1,
  ADD CONSTRAINT ck_node_capacity CHECK (max_inflight BETWEEN 1 AND 64);
