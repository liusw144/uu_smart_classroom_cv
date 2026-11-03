-- 新建数据库的默认排序规则可能与媒体主表不同，显式统一清理账本以保证UNION及精确编号比较。
ALTER TABLE media_storage_cleanup CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
ALTER TABLE media_storage_usage DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
