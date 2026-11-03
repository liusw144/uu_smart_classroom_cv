-- 全局准入互斥行；只串行化短事务入队，不持锁执行推理。
CREATE TABLE dispatch_guard (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO dispatch_guard VALUES (1);
ALTER TABLE edge_node ADD COLUMN max_pending INT NOT NULL DEFAULT 64,
  ADD CONSTRAINT ck_node_pending CHECK (max_pending BETWEEN 1 AND 10000);
