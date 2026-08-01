CREATE TABLE originated_transitions (
    row_id          BIGSERIAL    PRIMARY KEY,
    negotiation_id  VARCHAR(255) NOT NULL,
    ticket_id       VARCHAR(255) NOT NULL,
    expected_count  INTEGER      NOT NULL,
    received_count  INTEGER      NOT NULL DEFAULT 0,
    new_state       VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);