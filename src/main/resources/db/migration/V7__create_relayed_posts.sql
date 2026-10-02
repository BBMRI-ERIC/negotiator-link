CREATE TABLE relayed_posts (
    organization_ext_id VARCHAR(255) NOT NULL,
    ticket_id           VARCHAR(255) NOT NULL,
    post_id             VARCHAR(255) NOT NULL,
    relayed_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (organization_ext_id, ticket_id, post_id)
);