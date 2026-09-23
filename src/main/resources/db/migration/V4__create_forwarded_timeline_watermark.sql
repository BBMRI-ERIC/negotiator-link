CREATE TABLE forwarded_timeline_watermark (
    negotiation_id       VARCHAR(255) NOT NULL,
    helpdesk_ticket_id   VARCHAR(255) NOT NULL,
    last_timestamp       TIMESTAMP    NOT NULL,
    last_timestamp_count INTEGER      NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (negotiation_id, helpdesk_ticket_id)
);
