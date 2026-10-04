CREATE TABLE outbox_intent (
    row_id              BIGSERIAL    PRIMARY KEY,
    inbox_row_id        BIGINT       REFERENCES inbox_event (row_id),
    intent_type         VARCHAR(255) NOT NULL,
    target_kind         VARCHAR(255) NOT NULL,
    target_id           VARCHAR(255),
    negotiation_id      VARCHAR(255) NOT NULL,
    organization_ext_id VARCHAR(255) NOT NULL,
    payload             JSONB        NOT NULL,
    status              VARCHAR(255) NOT NULL,
    attempt_count       INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error          VARCHAR(255),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    delivered_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_outbox_dispatch ON outbox_intent (status, next_attempt_at);
CREATE INDEX ix_outbox_staleness ON outbox_intent (intent_type, target_id, status, row_id DESC);
