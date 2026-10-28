CREATE INDEX ix_inbox_status_next_attempt ON inbox_event (status, next_attempt_at);
