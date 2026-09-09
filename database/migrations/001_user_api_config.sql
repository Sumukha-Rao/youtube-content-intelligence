-- ============================================================================
-- Migration 001 — per-user API access
--
-- database/schema.sql only runs when the MySQL data directory is empty, so an
-- existing database needs these columns added by hand. (With ddl-auto=update the
-- backend also adds them on the next boot; this file is for managed databases
-- where the schema is applied manually, such as the Azure deployment.)
--
--   mysql -h <host> -u <user> -p yci < database/migrations/001_user_api_config.sql
--
-- Re-running it fails with "Duplicate column name" — that means it already ran.
-- ============================================================================

USE yci;

ALTER TABLE user_settings
    ADD COLUMN youtube_api_key     VARCHAR(512) NULL AFTER web_search_enabled,
    ADD COLUMN llm_base_url        VARCHAR(512) NULL AFTER youtube_api_key,
    ADD COLUMN llm_api_key         VARCHAR(512) NULL AFTER llm_base_url,
    ADD COLUMN llm_model           VARCHAR(128) NULL AFTER llm_api_key,
    ADD COLUMN web_search_provider VARCHAR(24)  NULL AFTER llm_model,
    ADD COLUMN searxng_base_url    VARCHAR(512) NULL AFTER web_search_provider;
