CREATE TABLE forwarded_negotiation_payload_attachment (
    negotiation_id VARCHAR(255) NOT NULL,
    attachment_id  VARCHAR(255) NOT NULL,
    forwarded_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (negotiation_id, attachment_id)
);
