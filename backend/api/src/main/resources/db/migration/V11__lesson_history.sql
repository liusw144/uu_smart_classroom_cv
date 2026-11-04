-- 历史记录只能捕获升级时的现值，必须标明来源，不能冒充开课时信息。
ALTER TABLE lesson_task
  ADD COLUMN class_id BIGINT NULL,
  ADD COLUMN course_name_snapshot VARCHAR(128) NULL,
  ADD COLUMN class_name_snapshot VARCHAR(128) NULL,
  ADD COLUMN grade_snapshot VARCHAR(64) NULL,
  ADD COLUMN student_count_snapshot INT NULL,
  ADD COLUMN term_snapshot VARCHAR(64) NULL,
  ADD COLUMN owner_name_snapshot VARCHAR(128) NULL,
  ADD COLUMN snapshot_captured_at TIMESTAMP(6) NULL,
  ADD COLUMN snapshot_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_BACKFILL',
  ADD COLUMN lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN scheduled_end_at TIMESTAMP(6) NULL,
  ADD COLUMN room_name VARCHAR(128) NOT NULL DEFAULT '',
  ADD COLUMN actual_started_at TIMESTAMP(6) NULL,
  ADD COLUMN actual_ended_at TIMESTAMP(6) NULL,
  ADD COLUMN archived_at TIMESTAMP(6) NULL,
  ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

UPDATE lesson_task t
JOIN course c ON c.tenant_id=t.tenant_id AND c.id=t.course_id AND c.owner_id=t.owner_id
JOIN school_class s ON s.tenant_id=c.tenant_id AND s.id=c.class_id AND s.owner_id=c.owner_id
JOIN school_account a ON a.tenant_id=t.tenant_id AND a.id=t.owner_id
SET t.class_id=s.id,t.course_name_snapshot=c.name,t.class_name_snapshot=s.name,
    t.grade_snapshot=s.grade,t.student_count_snapshot=s.student_count,t.term_snapshot=c.term,
    t.owner_name_snapshot=COALESCE(NULLIF(a.display_name,''),a.username),
    t.snapshot_captured_at=CURRENT_TIMESTAMP(6);

ALTER TABLE lesson_task
  MODIFY class_id BIGINT NOT NULL,
  MODIFY course_name_snapshot VARCHAR(128) NOT NULL,
  MODIFY class_name_snapshot VARCHAR(128) NOT NULL,
  MODIFY grade_snapshot VARCHAR(64) NOT NULL,
  MODIFY student_count_snapshot INT NOT NULL,
  MODIFY term_snapshot VARCHAR(64) NOT NULL,
  MODIFY owner_name_snapshot VARCHAR(128) NOT NULL,
  MODIFY snapshot_captured_at TIMESTAMP(6) NOT NULL,
  ADD CONSTRAINT fk_task_historical_class FOREIGN KEY (tenant_id,class_id,owner_id) REFERENCES school_class(tenant_id,id,owner_id),
  ADD CONSTRAINT ck_task_version CHECK (row_version>=0),
  ADD CONSTRAINT ck_task_snapshot CHECK (snapshot_source IN ('CREATED','REASSIGNED','LEGACY_BACKFILL') AND student_count_snapshot BETWEEN 0 AND 500),
  ADD CONSTRAINT ck_task_period CHECK (scheduled_end_at IS NULL OR (scheduled_at IS NOT NULL AND scheduled_end_at>scheduled_at)),
  ADD CONSTRAINT ck_task_lifecycle CHECK (
    (lifecycle_status IN ('PLANNED','LEGACY') AND actual_started_at IS NULL AND actual_ended_at IS NULL AND archived_at IS NULL)
    OR (lifecycle_status='IN_PROGRESS' AND actual_started_at IS NOT NULL AND actual_ended_at IS NULL AND archived_at IS NULL)
    OR (lifecycle_status='COMPLETED' AND actual_started_at IS NOT NULL AND actual_ended_at IS NOT NULL AND actual_ended_at>actual_started_at AND archived_at IS NULL)
    OR (lifecycle_status='ARCHIVED' AND archived_at IS NOT NULL AND ((actual_started_at IS NULL AND actual_ended_at IS NULL) OR (actual_started_at IS NOT NULL AND actual_ended_at IS NOT NULL AND actual_ended_at>actual_started_at)))
  );

-- 视图保留旧查询入口，但不再通过可变课程推导历史班级。
CREATE OR REPLACE VIEW lesson_task_view AS SELECT t.* FROM lesson_task t;

CREATE TABLE lesson_task_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(128) NOT NULL,
  action VARCHAR(32) NOT NULL,
  from_status VARCHAR(24) NULL,
  to_status VARCHAR(24) NOT NULL,
  task_version BIGINT NOT NULL,
  note VARCHAR(512) NOT NULL,
  context_json JSON NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_lesson_event_version (tenant_id,task_id,task_version),
  INDEX ix_lesson_event (tenant_id,task_id,id),
  FOREIGN KEY (tenant_id,task_id,owner_id) REFERENCES lesson_task(tenant_id,id,owner_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
