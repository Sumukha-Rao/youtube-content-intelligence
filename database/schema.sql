-- ============================================================================
-- YouTube Content Intelligence — MySQL schema
-- Hibernate can also create these tables (ddl-auto=update), but this file is the
-- authoritative schema and is loaded by docker-compose on first boot.
-- ============================================================================

CREATE DATABASE IF NOT EXISTS yci CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yci;

-- ----------------------------------------------------------------------------
-- Accounts
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt; never the plaintext
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Opaque bearer tokens, so a session can be revoked by deleting a row.
CREATE TABLE IF NOT EXISTS auth_tokens (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    token       VARCHAR(128) NOT NULL UNIQUE,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME NOT NULL,
    CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_tokens_user (user_id)
) ENGINE=InnoDB;

-- Ingestion + notification preferences, reused by every run.
CREATE TABLE IF NOT EXISTS user_settings (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                  BIGINT NOT NULL UNIQUE,
    retrieval_mode           VARCHAR(24) NOT NULL DEFAULT 'LAST_N_VIDEOS',  -- LAST_N_VIDEOS | LAST_N_DAYS
    retrieval_value          INT NOT NULL DEFAULT 15,
    own_comment_limit        INT NOT NULL DEFAULT 1000,
    competitor_comment_limit INT NOT NULL DEFAULT 200,
    web_search_enabled       BOOLEAN NOT NULL DEFAULT FALSE,  -- opt-in; see UserSettings
    -- Per-user API access. NULL in any of these means "use the server's value",
    -- which is what every account does until it saves its own. The two key columns
    -- hold ciphertext (prefixed `enc:`) whenever APP_SECRET_KEY is set.
    youtube_api_key          VARCHAR(512) NULL,
    llm_base_url             VARCHAR(512) NULL,
    llm_api_key              VARCHAR(512) NULL,
    llm_model                VARCHAR(128) NULL,
    web_search_provider      VARCHAR(24)  NULL,             -- searxng | duckduckgo | none
    searxng_base_url         VARCHAR(512) NULL,
    notify_enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    notify_frequency         VARCHAR(16) NOT NULL DEFAULT 'WEEKLY',         -- DAILY | WEEKLY
    notify_email             VARCHAR(255) NULL,
    last_notified_at         DATETIME NULL,
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- The creator's own channel (at most one per user)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS channels (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    youtube_channel_id  VARCHAR(64) NOT NULL,
    channel_name        VARCHAR(255),
    channel_url         VARCHAR(512),
    description         TEXT,
    subscriber_count    BIGINT,
    video_count         BIGINT,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_synced_at      DATETIME NULL,
    CONSTRAINT fk_channels_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_channels_user_ytid (user_id, youtube_channel_id),
    INDEX idx_channels_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS videos (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id        BIGINT NOT NULL,
    youtube_video_id  VARCHAR(32) NOT NULL,
    title             VARCHAR(512),
    description       MEDIUMTEXT,
    published_at      DATETIME NULL,
    duration          VARCHAR(32),
    view_count        BIGINT,
    like_count        BIGINT,
    comment_count     BIGINT,
    thumbnail_url     VARCHAR(512),
    transcript        LONGTEXT,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_videos_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE,
    -- Per channel, not global: two users may track the same YouTube channel and
    -- each needs their own copy of its videos.
    UNIQUE KEY uq_videos_channel_ytid (channel_id, youtube_video_id),
    INDEX idx_videos_channel (channel_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS comments (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    video_id            BIGINT NOT NULL,
    youtube_comment_id  VARCHAR(64) NOT NULL,
    parent_comment_id   VARCHAR(64) NULL,
    text                MEDIUMTEXT,
    published_at        DATETIME NULL,
    like_count          BIGINT,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comments_video FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE,
    UNIQUE KEY uq_comments_video_ytid (video_id, youtube_comment_id),
    INDEX idx_comments_video (video_id)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- Competitor channels, their videos and their top comments
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS competitor_channels (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                 BIGINT NOT NULL,
    youtube_channel_id      VARCHAR(64) NOT NULL,
    channel_name            VARCHAR(255),
    channel_url             VARCHAR(512),
    last_checked_at         DATETIME NULL,
    last_video_published_at DATETIME NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_competitors_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_competitor_user_ytid (user_id, youtube_channel_id),
    INDEX idx_competitors_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS competitor_videos (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    competitor_channel_id   BIGINT NOT NULL,
    youtube_video_id        VARCHAR(32) NOT NULL,
    title                   VARCHAR(512),
    description             MEDIUMTEXT,
    published_at            DATETIME NULL,
    duration                VARCHAR(32),
    view_count              BIGINT,
    like_count              BIGINT,
    comment_count           BIGINT,
    transcript              LONGTEXT,
    analyzed                BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_compvideos_channel FOREIGN KEY (competitor_channel_id)
        REFERENCES competitor_channels(id) ON DELETE CASCADE,
    UNIQUE KEY uq_compvideos_channel_ytid (competitor_channel_id, youtube_video_id),
    INDEX idx_compvideos_channel (competitor_channel_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS competitor_comments (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    competitor_video_id  BIGINT NOT NULL,
    youtube_comment_id   VARCHAR(64) NOT NULL,
    text                 MEDIUMTEXT,
    published_at         DATETIME NULL,
    like_count           BIGINT,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_compcomments_video FOREIGN KEY (competitor_video_id)
        REFERENCES competitor_videos(id) ON DELETE CASCADE,
    UNIQUE KEY uq_compcomments_video_ytid (competitor_video_id, youtube_comment_id),
    INDEX idx_compcomments_video (competitor_video_id)
) ENGINE=InnoDB;

-- ----------------------------------------------------------------------------
-- Idea generation runs — async job record and stored result in one table.
-- The full prompt is kept so the user can inspect exactly what the model saw.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idea_runs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    status           VARCHAR(16) NOT NULL,     -- QUEUED | RUNNING | COMPLETED | FAILED
    progress         INT NOT NULL DEFAULT 0,
    message          VARCHAR(512),
    prompt           LONGTEXT,
    result           LONGTEXT,
    model            VARCHAR(128),
    web_search_used  BOOLEAN NOT NULL DEFAULT FALSE,
    notified         BOOLEAN NOT NULL DEFAULT FALSE,
    error_message    TEXT,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     DATETIME NULL,
    CONSTRAINT fk_runs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_runs_user (user_id)
) ENGINE=InnoDB;
