-- 分析/报告被删除后仍保留曾经使用的事实，不能借清空记录解锁历史教务资料。
ALTER TABLE lesson_task ADD COLUMN metadata_frozen BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE lesson_task t SET metadata_frozen=TRUE
WHERE t.lifecycle_status<>'PLANNED'
   OR EXISTS(SELECT 1 FROM detection_job j WHERE j.tenant_id=t.tenant_id AND j.task_id=t.id)
   OR EXISTS(SELECT 1 FROM analysis_report r WHERE r.tenant_id=t.tenant_id AND r.task_id=t.id);
