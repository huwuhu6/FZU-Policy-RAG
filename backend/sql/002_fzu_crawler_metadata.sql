-- FZU Crawler V1 source metadata. Run after 001_baseline_schema.sql.
-- No table is dropped or rebuilt; the fields are document-level facts.

USE campus_knowledge;

ALTER TABLE knowledge_document
    ADD COLUMN source_url VARCHAR(1024) NULL AFTER file_hash,
    ADD COLUMN artifact_url VARCHAR(1024) NULL AFTER source_url,
    ADD COLUMN source_section VARCHAR(64) NULL AFTER artifact_url,
    ADD COLUMN publish_date DATE NULL AFTER source_section,
    ADD COLUMN handbook_year INT NULL AFTER publish_date;
