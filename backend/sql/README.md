# Baseline schema

`001_baseline_schema.sql` 根据当前 Java Entity、Mapper 与 Service contract 恢复；upstream 未版本化完整建表脚本。

在全新 MySQL 8.0+ 实例执行该脚本即可创建 `campus_knowledge` 及当前 baseline 所需的 8 张表。脚本只使用 `CREATE DATABASE IF NOT EXISTS` 和 `CREATE TABLE IF NOT EXISTS`，不包含默认用户、删除数据或破坏性操作。

关键 contract：

- `etl_job.active_key` 唯一且允许为 `NULL`，保证同文档同类型活跃任务并发入队幂等，同时允许已完成/彻底失败任务再次入队。
- `sys_user.username` 唯一；`knowledge_document.doc_uuid` 唯一；`knowledge_parent_block.parent_block_id` 唯一。
- ACL refresh task 按 `(doc_uuid, target_acl_version)` 唯一；chat session 按 `(conversation_id, user_id)` 唯一；chat message 按 `(session_id, message_index)` 唯一以保持消息顺序。
- `tags`、ACL 列表使用 JSON；父块/消息正文使用 LONGTEXT；消息序列化数据使用 MEDIUMBLOB/LONGBLOB。
- 不添加数据库 Foreign Key，避免改变当前应用层删除、重试和补偿语义。

后续 schema 演进应新增编号 SQL 文件，不直接覆盖该 baseline。
