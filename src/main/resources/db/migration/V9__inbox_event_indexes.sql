CREATE INDEX ix_inbox_status_next_attempt ON inbox_event (status, next_attempt_at);

CREATE INDEX ix_inbox_received_retry ON inbox_event (status, attempt_count)
    WHERE status = 'RECEIVED' AND attempt_count > 0;