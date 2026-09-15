CREATE TABLE ticket_mapping (
    negotiation_id      VARCHAR(255) NOT NULL,
    helpdesk_ticket_id  VARCHAR(255) NOT NULL,
    resource_id         VARCHAR(255) NOT NULL,
    resource_source_id  VARCHAR(255) NOT NULL,
    organization_id     VARCHAR(255) NOT NULL,
    organization_ext_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (negotiation_id, helpdesk_ticket_id, resource_id)
);