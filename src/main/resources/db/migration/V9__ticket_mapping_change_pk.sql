ALTER TABLE ticket_mapping DROP CONSTRAINT ticket_mapping_pkey;
ALTER TABLE ticket_mapping ADD PRIMARY KEY (helpdesk_ticket_id, organization_ext_id);
