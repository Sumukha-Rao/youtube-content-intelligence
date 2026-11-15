-- Run against an existing MySQL database before retrying a failed idea run.
-- Hibernate ddl-auto=update adds the scoped unique key but does not remove the
-- legacy global key. That old key prevents two users tracking the same video
-- from each storing its comments. No comment rows are deleted or reassigned.
-- Safe to run repeatedly; discovers legacy index names rather than assuming
-- Hibernate generated the same name in every database.

-- Keep duplicate protection within each user's local competitor video first.
SET @has_scoped_key = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'competitor_comments'
          AND NON_UNIQUE = 0
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) =
               'competitor_video_id,youtube_comment_id'
    ) AS scoped_keys
);
SET @migration_sql = IF(@has_scoped_key > 0, 'SELECT 1',
    'ALTER TABLE competitor_comments ADD UNIQUE KEY uq_compcomments_video_ytid (competitor_video_id, youtube_comment_id)');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- Remove only uniqueness on youtube_comment_id alone; preserve all other keys.
SET @legacy_keys = (
    SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(INDEX_NAME, '`', '``'), '`')
                        SEPARATOR ', ')
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'competitor_comments'
          AND NON_UNIQUE = 0
          AND INDEX_NAME <> 'PRIMARY'
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'youtube_comment_id'
    ) AS legacy_keys
);
SET @migration_sql = IF(@legacy_keys IS NULL, 'SELECT 1',
    CONCAT('ALTER TABLE competitor_comments ', @legacy_keys));
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
