CREATE TABLE inbox_event (
    row_id       BIGSERIAL    PRIMARY KEY,
    source       VARCHAR(255) NOT NULL,
    delivery_id  VARCHAR(255) NOT NULL,
    event_type   VARCHAR(255),
    payload      JSONB        NOT NULL,
    status       VARCHAR(255) NOT NULL,
    skip_reason  VARCHAR(255),
    last_error   VARCHAR(255),
    attempt_count   INTEGER   NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_inbox_delivery UNIQUE (source, delivery_id)
);

CREATE INDEX ix_inbox_status_received ON inbox_event (status, received_at);
