-- 当前执行器严格单槽；移除未参与调度的并行槽字段，防止运维误以为修改该值即可扩容。
-- 节点的待执行队列仍由max_pending限制；增加吞吐需部署独立节点及Outbox并完成容量验收。
ALTER TABLE edge_node DROP CHECK ck_node_capacity, DROP COLUMN max_inflight;
