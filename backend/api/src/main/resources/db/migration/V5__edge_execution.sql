CREATE TABLE edge_node (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  last_seen_at TIMESTAMP(6) NULL,
  UNIQUE KEY uq_node_scope (tenant_id,id),
  FOREIGN KEY (tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

ALTER TABLE detection_job
  DROP CHECK detection_job_chk_1,
  ADD COLUMN media_kind VARCHAR(16) NOT NULL DEFAULT 'IMAGE',
  ADD COLUMN node_id VARCHAR(64) NULL,
  ADD COLUMN lease_id CHAR(36) NULL,
  ADD COLUMN lease_until TIMESTAMP(6) NULL,
  ADD COLUMN attempt INT NOT NULL DEFAULT 0,
  ADD COLUMN result_hash CHAR(64) NULL,
  ADD COLUMN result_json MEDIUMTEXT NULL,
  ADD COLUMN artifact_content_type VARCHAR(32) NULL,
  ADD UNIQUE KEY uq_job_scope (tenant_id,id,owner_id),
  ADD INDEX ix_job_claim (tenant_id,node_id,status,created_at),
  ADD CONSTRAINT fk_job_node FOREIGN KEY (tenant_id,node_id) REFERENCES edge_node(tenant_id,id),
  ADD CONSTRAINT ck_job_status CHECK (status IN ('QUEUED','RUNNING','FAILED','SUCCEEDED','CANCELLED')),
  ADD CONSTRAINT ck_job_media CHECK (media_kind IN ('IMAGE','VIDEO','CAMERA'));

ALTER TABLE media_asset DROP CHECK media_asset_chk_1,
  ADD CONSTRAINT ck_asset_size CHECK (size_bytes > 0 AND size_bytes <= 33554432);

ALTER TABLE analysis_report
  ADD COLUMN detection_job_id CHAR(36) NULL,
  ADD COLUMN ai_model VARCHAR(80) NULL,
  ADD COLUMN ai_weight_hash CHAR(64) NULL,
  ADD CONSTRAINT fk_report_detection FOREIGN KEY (tenant_id,detection_job_id,owner_id) REFERENCES detection_job(tenant_id,id,owner_id);

CREATE OR REPLACE VIEW detection_job_view AS
SELECT j.*, t.name AS task_name FROM detection_job j
JOIN lesson_task t ON t.id=j.task_id AND t.tenant_id=j.tenant_id AND t.owner_id=j.owner_id;
