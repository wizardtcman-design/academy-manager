-- ============================================================
-- Migration 0008: free the /start link-token hash for Stage 2
-- The one-time telegram link token is cleared (empty string) after its first
-- successful use, so MANY students legitimately hold an empty hash at the
-- same time. The 1:1 UNIQUE index from 0001 therefore cannot stay; lookups
-- remain exact-match by hash through a normal (non-unique) index.
-- Safe for existing databases: dropping an index never touches row data,
-- and it is a no-op when 0001 was never applied.
-- ============================================================

DROP INDEX IF EXISTS idx_students_link_hash;

CREATE INDEX IF NOT EXISTS idx_students_link_hash_lookup ON students(telegram_link_hash);
