CREATE TABLE media_asset (
  id CHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  filename VARCHAR(128) NOT NULL,
  content_type VARCHAR(32) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_asset_scope (tenant_id,id,owner_id),
  INDEX ix_asset_owner (tenant_id,owner_id),
  FOREIGN KEY (tenant_id,owner_id) REFERENCES school_account(tenant_id,id),
  CHECK (size_bytes > 0 AND size_bytes <= 10485760)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

ALTER TABLE school_account ADD COLUMN avatar_asset_id CHAR(36) NULL,
  ADD CONSTRAINT fk_account_avatar FOREIGN KEY (tenant_id,avatar_asset_id,id) REFERENCES media_asset(tenant_id,id,owner_id);

CREATE TABLE detection_job (
  id CHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  asset_id CHAR(36) NOT NULL,
  model_id BIGINT NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  INDEX ix_job_owner (tenant_id,owner_id,created_at),
  FOREIGN KEY (tenant_id,task_id,owner_id) REFERENCES lesson_task(tenant_id,id,owner_id),
  FOREIGN KEY (tenant_id,asset_id,owner_id) REFERENCES media_asset(tenant_id,id,owner_id),
  FOREIGN KEY (tenant_id,model_id) REFERENCES model_catalog(tenant_id,id),
  CHECK (status IN ('RUNNING','FAILED','SUCCEEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE VIEW detection_job_view AS
SELECT j.*, t.name AS task_name FROM detection_job j
JOIN lesson_task t ON t.id=j.task_id AND t.tenant_id=j.tenant_id AND t.owner_id=j.owner_id;

CREATE TABLE analysis_report (
  id CHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  title VARCHAR(128) NOT NULL,
  content TEXT NOT NULL,
  source VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  INDEX ix_report_owner (tenant_id,owner_id,created_at),
  FOREIGN KEY (tenant_id,task_id,owner_id) REFERENCES lesson_task(tenant_id,id,owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
