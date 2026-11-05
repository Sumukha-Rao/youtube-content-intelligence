-- Apply once to an existing database when Hibernate ddl-auto=update is disabled.
-- No new tables. Historic idea runs retain their existing Markdown results.
ALTER TABLE channels
    ADD COLUMN content_language VARCHAR(32) NULL,
    ADD COLUMN competitor_suggestions LONGTEXT NULL;
ALTER TABLE competitor_videos
    ADD COLUMN video_url VARCHAR(512) NULL,
    ADD COLUMN channel_name VARCHAR(255) NULL;
ALTER TABLE idea_runs
    ADD COLUMN recommendation_data LONGTEXT NULL;
