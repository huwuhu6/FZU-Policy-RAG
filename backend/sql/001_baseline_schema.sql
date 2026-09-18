-- FZU-Policy-RAG baseline schema.
-- 根据当前 Java Entity / Mapper / Service contract 恢复；upstream 未版本化建表脚本。
-- 本脚本仅创建缺失对象，不删除或清空已有数据库对象。

CREATE DATABASE IF NOT EXISTS campus_knowledge
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE campus_knowledge;

CREATE TABLE IF NOT EXISTS sys_user (
    id                  VARCHAR(64)  NOT NULL,
    username            VARCHAR(128) NOT NULL,
    password            VARCHAR(255),
    role                VARCHAR(32),
    dept_id             VARCHAR(128),
    dept_name           VARCHAR(255),
    default_space_code  VARCHAR(128),
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    create_user_id      VARCHAR(64),
    create_user_name    VARCHAR(128),
    create_date         DATETIME(6),
    update_user_id      VARCHAR(64),
    update_user_name    VARCHAR(128),
    update_date         DATETIME(6),
    remark              TEXT,
    version             INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id                  VARCHAR(64)  NOT NULL,
    doc_uuid            VARCHAR(128) NOT NULL,
    file_name           VARCHAR(512),
    status              VARCHAR(32),
    file_hash           VARCHAR(128),
    tags                JSON,
    space_code          VARCHAR(128),
    owner_dept_id       VARCHAR(128),
    allowed_roles       JSON,
    allowed_dept_ids    JSON,
    is_public           BOOLEAN,
    acl_version         INT,
    acl_refresh_status  VARCHAR(32),
    acl_refresh_error   TEXT,
    acl_refresh_time    DATETIME(6),
    error_message       TEXT,
    error_stack         TEXT,
    retry_count         INT,
    object_key          VARCHAR(1024),
    create_user_id      VARCHAR(64),
    create_user_name    VARCHAR(128),
    create_date         DATETIME(6),
    update_user_id      VARCHAR(64),
    update_user_name    VARCHAR(128),
    update_date         DATETIME(6),
    remark              TEXT,
    version             INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_doc_uuid (doc_uuid),
    KEY idx_knowledge_document_status_create (status, create_date),
    KEY idx_knowledge_document_file_hash (file_hash),
    KEY idx_knowledge_document_space_code (space_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_parent_block (
    id                   VARCHAR(64)  NOT NULL,
    parent_block_id      VARCHAR(256) NOT NULL,
    doc_uuid             VARCHAR(128) NOT NULL,
    parent_index         INT,
    content              LONGTEXT,
    file_name            VARCHAR(512),
    page_start           INT,
    page_end             INT,
    space_code           VARCHAR(128),
    tags                 JSON,
    acl_version          INT,
    chunk_schema_version INT,
    create_user_id       VARCHAR(64),
    create_user_name     VARCHAR(128),
    create_date          DATETIME(6),
    update_user_id       VARCHAR(64),
    update_user_name     VARCHAR(128),
    update_date          DATETIME(6),
    remark               TEXT,
    version              INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_parent_block_parent_block_id (parent_block_id),
    KEY idx_parent_block_doc_uuid (doc_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS knowledge_acl_refresh_task (
    id                  VARCHAR(64)  NOT NULL,
    doc_uuid            VARCHAR(128) NOT NULL,
    target_acl_version  INT          NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    retry_count         INT          NOT NULL DEFAULT 0,
    last_error          TEXT,
    next_retry_time     DATETIME(6),
    create_user_id      VARCHAR(64),
    create_user_name    VARCHAR(128),
    create_date         DATETIME(6),
    update_user_id      VARCHAR(64),
    update_user_name    VARCHAR(128),
    update_date         DATETIME(6),
    remark              TEXT,
    version             INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_acl_refresh_doc_version (doc_uuid, target_acl_version),
    KEY idx_acl_refresh_status_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS etl_job (
    id                  VARCHAR(64)  NOT NULL,
    job_uuid            VARCHAR(64),
    doc_uuid            VARCHAR(128) NOT NULL,
    job_type            VARCHAR(64)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    file_path           VARCHAR(1024),
    file_name           VARCHAR(512),
    tags                JSON,
    retry_count         INT,
    max_retry_count     INT,
    next_retry_time     DATETIME(6),
    locked_by           VARCHAR(128),
    locked_until        DATETIME(6),
    started_at          DATETIME(6),
    finished_at         DATETIME(6),
    last_error          TEXT,
    error_stack         TEXT,
    active_key          VARCHAR(256),
    object_key          VARCHAR(1024),
    create_user_id      VARCHAR(64),
    create_user_name    VARCHAR(128),
    create_date         DATETIME(6),
    update_user_id      VARCHAR(64),
    update_user_name    VARCHAR(128),
    update_date         DATETIME(6),
    remark              TEXT,
    version             INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_etl_job_job_uuid (job_uuid),
    UNIQUE KEY uk_etl_job_active_key (active_key),
    KEY idx_etl_job_status_retry (status, next_retry_time),
    KEY idx_etl_job_status_lease (status, locked_until),
    KEY idx_etl_job_doc_type_status (doc_uuid, job_type, status),
    KEY idx_etl_job_create_date (create_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chat_session (
    id               VARCHAR(64)  NOT NULL,
    conversation_id  VARCHAR(128) NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    title            VARCHAR(255),
    title_status     VARCHAR(32),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    last_message_at  DATETIME(6),
    create_date      DATETIME(6),
    update_date      DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_session_conversation_user (conversation_id, user_id),
    KEY idx_chat_session_user_deleted_last (user_id, deleted, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chat_message (
    id               VARCHAR(128) NOT NULL,
    session_id       VARCHAR(64)  NOT NULL,
    conversation_id  VARCHAR(128),
    user_id          VARCHAR(64),
    role             VARCHAR(32),
    content          LONGTEXT,
    message_blob     MEDIUMBLOB,
    message_index    INT          NOT NULL,
    model_id         VARCHAR(128),
    mode             VARCHAR(64),
    create_date      DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_message_session_index (session_id, message_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS chat_memory_snapshot (
    conversation_id  VARCHAR(128) NOT NULL,
    message_blob     LONGBLOB,
    message_count    INT,
    serializer       VARCHAR(64),
    create_date      DATETIME(6),
    update_date      DATETIME(6),
    PRIMARY KEY (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
