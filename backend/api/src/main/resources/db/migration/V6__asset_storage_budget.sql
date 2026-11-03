ALTER TABLE media_asset
  ADD COLUMN storage_state VARCHAR(12) NOT NULL DEFAULT 'READY',
  ADD CONSTRAINT ck_asset_storage_state CHECK (storage_state IN ('PENDING','READY')),
  ADD INDEX ix_asset_pending (storage_state,created_at);

-- 占用包含预留、已发布及尚未物理删除的文件；零学校与零负责人行是全局和学校计数。
CREATE TABLE media_storage_usage (
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  used_bytes BIGINT NOT NULL DEFAULT 0,
  file_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id,owner_id),
  CONSTRAINT ck_storage_usage CHECK (used_bytes >= 0 AND file_count >= 0)
) ENGINE=InnoDB;

INSERT INTO media_storage_usage (tenant_id,owner_id,used_bytes,file_count)
  SELECT 0,0,COALESCE(SUM(GREATEST(size_bytes,8192)),0),COUNT(*) FROM media_asset;
INSERT INTO media_storage_usage (tenant_id,owner_id,used_bytes,file_count)
  SELECT tenant_id,0,SUM(GREATEST(size_bytes,8192)),COUNT(*) FROM media_asset GROUP BY tenant_id;
INSERT INTO media_storage_usage (tenant_id,owner_id,used_bytes,file_count)
  SELECT tenant_id,owner_id,SUM(GREATEST(size_bytes,8192)),COUNT(*) FROM media_asset GROUP BY tenant_id,owner_id;

-- 删除元数据成功后仍保留计费账本，物理删除确认后才能释放。
CREATE TABLE media_storage_cleanup (
  asset_id CHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  charged_bytes BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX ix_storage_cleanup_age (created_at),
  CONSTRAINT ck_storage_cleanup_size CHECK (charged_bytes > 0)
) ENGINE=InnoDB;
