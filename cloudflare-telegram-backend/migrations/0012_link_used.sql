-- 0012: one-time /start link consumption marker.
-- The /start claim flips telegram_link_used to 1 in the SAME statement that
-- binds the chat, so two accounts racing on one link can never both succeed
-- and a consumed link reports a distinct "already used" state afterwards.
ALTER TABLE students ADD COLUMN telegram_link_used INTEGER NOT NULL DEFAULT 0;
