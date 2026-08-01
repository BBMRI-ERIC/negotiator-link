CREATE TABLE originated_posts (
    row_id          BIGSERIAL    PRIMARY KEY,
    text_hash       VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL,
    ticket_id       VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);