ALTER TABLE originated_post
      DROP COLUMN organization_id,
      ADD COLUMN organization_ext_id VARCHAR(255) NOT NULL DEFAULT '';

ALTER TABLE originated_post
      ALTER COLUMN organization_ext_id DROP DEFAULT;