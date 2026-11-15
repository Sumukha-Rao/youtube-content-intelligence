"""MySQL regression test: python3 database/tests/test_competitor_comment_migration.py.

Uses the Compose MySQL service and disposable databases; leaves app data untouched.
"""

from pathlib import Path
import os
import subprocess
import uuid


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (ROOT / "database/migrations/003_competitor_comment_uniqueness.sql").read_text()


def mysql(sql, database=None, check=True):
    container = os.environ.get("YCI_TEST_MYSQL_CONTAINER")
    command = (["docker", "exec", "-i", container] if container
               else ["docker", "compose", "exec", "-T", "mysql"]) + ["sh", "-c",
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --batch --skip-column-names "$@"',
        "sh",
    ]
    if database:
        command.append(database)
    result = subprocess.run(command, input=sql, text=True, capture_output=True, cwd=ROOT)
    if check and result.returncode:
        raise AssertionError(result.stderr)
    return result


def test_schema(label, keys):
    database = "yci_migration_test_" + uuid.uuid4().hex
    mysql(f"CREATE DATABASE `{database}`;")
    try:
        mysql("""
            CREATE TABLE competitor_comments (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                competitor_video_id BIGINT NOT NULL,
                youtube_comment_id VARCHAR(64) NOT NULL,
                text MEDIUMTEXT,
        """ + keys + """
            ) ENGINE=InnoDB;
            INSERT INTO competitor_comments (competitor_video_id, youtube_comment_id, text)
            VALUES (101, 'shared-comment', 'original account');
        """, database)
        mysql(MIGRATION, database)
        mysql("""
            INSERT INTO competitor_comments (competitor_video_id, youtube_comment_id, text)
            VALUES (202, 'shared-comment', 'second account');
        """, database)
        duplicate = mysql("""
            INSERT INTO competitor_comments (competitor_video_id, youtube_comment_id)
            VALUES (202, 'shared-comment');
        """, database, check=False)
        assert duplicate.returncode != 0 and "1062" in duplicate.stderr, duplicate.stderr
        mysql(MIGRATION, database)  # Repeat runs must preserve data and protection.
        rows = mysql("""
            SELECT competitor_video_id, youtube_comment_id, text
            FROM competitor_comments ORDER BY competitor_video_id;
        """, database).stdout.strip().splitlines()
        assert rows == [
            "101\tshared-comment\toriginal account",
            "202\tshared-comment\tsecond account",
        ], rows
        print(f"PASS: {label}: shared comments allowed, same-video duplicates rejected, rows preserved, repeat safe")
    finally:
        mysql(f"DROP DATABASE `{database}`;")


if __name__ == "__main__":
    test_schema("legacy", "UNIQUE KEY arbitrary_legacy_name (youtube_comment_id)")
    test_schema("Hibernate-updated legacy", """
        UNIQUE KEY arbitrary_legacy_name (youtube_comment_id),
        UNIQUE KEY scoped_key (competitor_video_id, youtube_comment_id)
    """)
    test_schema("current", "UNIQUE KEY scoped_key (competitor_video_id, youtube_comment_id)")
