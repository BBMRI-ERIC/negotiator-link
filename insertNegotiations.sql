\c negotiator

-- 10 copies of negotiation '550e8400-e29b-41d4-a716-446655440000' ("HIV Cohort Study")
-- New negotiation IDs: 550e8400-e29b-41d4-a716-446655440010 through ...0019
-- negotiation_lifecycle_record IDs start at 100 to avoid conflicts with existing rows
-- negotiation_resource_lifecycle_record IDs start at 100 to avoid conflicts with existing rows
--
-- 10 copies of negotiation '550e8400-e29b-41d4-a716-446655440003' ("Lung Cancer Genomics Study")
-- New negotiation IDs: 550e8400-e29b-41d4-a716-446655440030 through ...0039
-- negotiation_lifecycle_record IDs: 120–139
-- negotiation_resource_lifecycle_record IDs: 120–129
-- post UUIDs: 550e8400-e29b-41d4-a716-556655440030 through ...0039

-- ============================================================
-- New resource id=3: copy of resource id=2 (same org=2, same representative person_id=2)
-- New resource id=4: copy of resource id=1 (same org=1, same representative person_id=2)
-- Must be inserted before any negotiation_resource_link references them
-- ============================================================
INSERT INTO resource (id, name, description, source_id, discovery_service_id, access_form_id, organization_id)
VALUES (3, 'Test collection 3', 'This is the third test collection',
        'bbmri-eric:ID:CZ_MMCI:collection:LTS2', 1, 3, 2)
ON CONFLICT DO NOTHING;

INSERT INTO resource_representative_link (resource_id, person_id)
VALUES (3, 2)
ON CONFLICT DO NOTHING;

INSERT INTO resource (id, name, description, source_id, discovery_service_id, access_form_id, organization_id)
VALUES (4, 'Dummy test collection 2', 'This is the second dummy test collection',
        'bbmri-eric:ID:SE_890:collection:dummy_collection_2', 1, 1, 1)
ON CONFLICT DO NOTHING;

INSERT INTO resource_representative_link (resource_id, person_id)
VALUES (4, 2)
ON CONFLICT DO NOTHING;

-- ============================================================
-- negotiation
-- ============================================================
INSERT INTO negotiation (id, creation_date, modified_date, current_state, payload, public_posts_enabled, private_posts_enabled, created_by, modified_by, human_readable, discovery_service_id)
VALUES
    ('550e8400-e29b-41d4-a716-446655440010', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study1","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440011', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study2","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440012', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study3","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440013', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study4","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440014', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study5","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440015', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study6","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440016', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study7","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440017', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study8","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440018', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study9","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1),
    ('550e8400-e29b-41d4-a716-446655440019', '2024-01-01 10:00:00', '2024-01-01 10:00:00', 'SUBMITTED', '{"project":{"title":"HIV Cohort Study10","disease-code":"B23.0","objective":"To investigate disease progression and comorbidities in people living with HIV/AIDS","organization":"University Medical Center","profit":"No","acknowledgment":"Funded by National Institute of Health Research","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"We seek access to HIV cohort samples with documented disease progression markers and comorbidity data","collection":"","donors":"150","samples":"serum","specifics":"CD4 count and viral load data required"},"ethics-vote":{"ethics-vote":"Approved by University Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '(((gender EQUALS male) OR (gender EQUALS female)) AND (diagnosis EQUALS B23.0))', 1)
ON CONFLICT DO NOTHING;

-- ============================================================
-- negotiation_lifecycle_record (DRAFT + SUBMITTED per copy)
-- ============================================================
INSERT INTO negotiation_lifecycle_record (id, creation_date, modified_date, created_by, modified_by, changed_to, negotiation_id)
VALUES
    (100, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440010'),
    (101, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440010'),
    (102, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440011'),
    (103, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440011'),
    (104, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440012'),
    (105, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440012'),
    (106, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440013'),
    (107, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440013'),
    (108, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440014'),
    (109, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440014'),
    (110, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440015'),
    (111, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440015'),
    (112, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440016'),
    (113, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440016'),
    (114, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440017'),
    (115, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440017'),
    (116, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440018'),
    (117, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440018'),
    (118, '2024-01-01 08:00:00', '2024-01-01 08:00:00', 1, 1, 'DRAFT',      '550e8400-e29b-41d4-a716-446655440019'),
    (119, '2024-01-01 09:30:00', '2024-01-01 09:30:00', 1, 1, 'SUBMITTED',   '550e8400-e29b-41d4-a716-446655440019')
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('negotiation_lifecycle_record', 'id'), coalesce((SELECT max(id) FROM negotiation_lifecycle_record), 1));

-- ============================================================
-- negotiation_resource_link (resource_id=1 AND resource_id=4, SUBMITTED per copy)
-- ============================================================
INSERT INTO negotiation_resource_link (negotiation_id, resource_id, current_state)
VALUES
    ('550e8400-e29b-41d4-a716-446655440010', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440011', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440012', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440013', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440014', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440015', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440016', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440017', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440018', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440019', 1, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440010', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440011', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440012', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440013', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440014', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440015', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440016', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440017', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440018', 4, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440019', 4, 'SUBMITTED')
ON CONFLICT DO NOTHING;

-- ============================================================
-- negotiation_resource_lifecycle_record (SUBMITTED + REPRESENTATIVE_CONTACTED per copy)
-- resource_id=1: IDs 100–119, resource_id=4: IDs 142–161
-- ============================================================
INSERT INTO negotiation_resource_lifecycle_record (id, created_by, creation_date, modified_date, modified_by, changed_to, negotiation_id, resource_id)
VALUES
    (100, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440010', 1),
    (101, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440010', 1),
    (102, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440011', 1),
    (103, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440011', 1),
    (104, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440012', 1),
    (105, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440012', 1),
    (106, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440013', 1),
    (107, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440013', 1),
    (108, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440014', 1),
    (109, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440014', 1),
    (110, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440015', 1),
    (111, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440015', 1),
    (112, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440016', 1),
    (113, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440016', 1),
    (114, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440017', 1),
    (115, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440017', 1),
    (116, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440018', 1),
    (117, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440018', 1),
    (118, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440019', 1),
    (119, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440019', 1),
    (142, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440010', 4),
    (143, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440010', 4),
    (144, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440011', 4),
    (145, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440011', 4),
    (146, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440012', 4),
    (147, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440012', 4),
    (148, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440013', 4),
    (149, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440013', 4),
    (150, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440014', 4),
    (151, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440014', 4),
    (152, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440015', 4),
    (153, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440015', 4),
    (154, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440016', 4),
    (155, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440016', 4),
    (156, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440017', 4),
    (157, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440017', 4),
    (158, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440018', 4),
    (159, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440018', 4),
    (160, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440019', 4),
    (161, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440019', 4)
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('negotiation_resource_lifecycle_record', 'id'), coalesce((SELECT max(id) FROM negotiation_resource_lifecycle_record), 1));

-- ============================================================
-- negotiation_resource_link and lifecycle records for negotiation ...0000 + resource 4
-- ============================================================
INSERT INTO negotiation_resource_link (negotiation_id, resource_id, current_state)
VALUES ('550e8400-e29b-41d4-a716-446655440000', 4, 'SUBMITTED')
ON CONFLICT DO NOTHING;

INSERT INTO negotiation_resource_lifecycle_record (id, created_by, creation_date, modified_date, modified_by, changed_to, negotiation_id, resource_id)
VALUES
    (140, 1, '2024-01-01 10:00:00', '2024-01-01 10:00:00', 1, 'SUBMITTED',               '550e8400-e29b-41d4-a716-446655440000', 4),
    (141, 2, '2024-01-01 10:15:00', '2024-01-01 10:15:00', 2, 'REPRESENTATIVE_CONTACTED', '550e8400-e29b-41d4-a716-446655440000', 4)
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('negotiation_resource_lifecycle_record', 'id'), coalesce((SELECT max(id) FROM negotiation_resource_lifecycle_record), 1));

-- ============================================================
-- post (2 posts per negotiation copy, unique UUIDs)
-- ============================================================
INSERT INTO post (id, creation_date, modified_date, created_by, modified_by, type, text, negotiation_id)
VALUES
    ('550e8400-e29b-41d4-a716-556655440010', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440010'),
    ('550e8400-e29b-41d4-a716-556655440011', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440010'),
    ('550e8400-e29b-41d4-a716-556655440012', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440011'),
    ('550e8400-e29b-41d4-a716-556655440013', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440011'),
    ('550e8400-e29b-41d4-a716-556655440014', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440012'),
    ('550e8400-e29b-41d4-a716-556655440015', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440012'),
    ('550e8400-e29b-41d4-a716-556655440016', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440013'),
    ('550e8400-e29b-41d4-a716-556655440017', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440013'),
    ('550e8400-e29b-41d4-a716-556655440018', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440014'),
    ('550e8400-e29b-41d4-a716-556655440019', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440014'),
    ('550e8400-e29b-41d4-a716-556655440020', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440015'),
    ('550e8400-e29b-41d4-a716-556655440021', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440015'),
    ('550e8400-e29b-41d4-a716-556655440022', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440016'),
    ('550e8400-e29b-41d4-a716-556655440023', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440016'),
    ('550e8400-e29b-41d4-a716-556655440024', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440017'),
    ('550e8400-e29b-41d4-a716-556655440025', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440017'),
    ('550e8400-e29b-41d4-a716-556655440026', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440018'),
    ('550e8400-e29b-41d4-a716-556655440027', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440018'),
    ('550e8400-e29b-41d4-a716-556655440028', '2024-01-01 09:45:00', '2024-01-01 09:45:00', 1, 1, 'PUBLIC', 'We are requesting access to HIV cohort samples for our research on disease progression. Please review our detailed access form.', '550e8400-e29b-41d4-a716-446655440019'),
    ('550e8400-e29b-41d4-a716-556655440029', '2024-01-01 10:20:00', '2024-01-01 10:20:00', 2, 2, 'PUBLIC', 'Thank you for your submission. We have received your request and will begin our internal assessment process.', '550e8400-e29b-41d4-a716-446655440019')
ON CONFLICT DO NOTHING;

-- ============================================================
-- 10 copies of negotiation '550e8400-e29b-41d4-a716-446655440003' ("Lung Cancer Genomics Study")
-- ============================================================

-- negotiation
INSERT INTO negotiation (id, creation_date, modified_date, current_state, payload, public_posts_enabled, private_posts_enabled, created_by, modified_by, human_readable, discovery_service_id)
VALUES
    ('550e8400-e29b-41d4-a716-446655440030', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study1","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440031', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study2","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440032', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study3","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440033', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study4","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440034', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study5","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440035', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study6","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440036', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study7","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440037', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study8","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440038', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study9","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1),
    ('550e8400-e29b-41d4-a716-446655440039', '2024-01-04 09:00:00', '2024-01-04 09:00:00', 'SUBMITTED', '{"project":{"title":"Lung Cancer Genomics Study10","disease-code":"C34.9,C34.1,C34.2","objective":"To characterize genomic alterations in non-small cell lung cancer to guide precision medicine approaches","organization":"Masaryk Memorial Cancer Institute","profit":"No","acknowledgment":"International Cancer Genomics Consortium","Multichoice test":["second_choice","third_choice"],"Single test":"second_choice","Bool test":"Yes"},"request":{"description":"Seeking tumor tissue and matched normal tissue samples from lung cancer patients for comprehensive genomic analysis","collection":"","donors":"100","samples":"tissue","specifics":"NSCLC subtypes preferred with pathology confirmation"},"ethics-vote":{"ethics-vote":"Approved by Cancer Research Ethics Committee","ethics-vote-attachment":null}}', true, false, 1, 1, '((diagnosis EQUALS C34.9) OR (diagnosis EQUALS C34.1) OR (diagnosis EQUALS C34.2))', 1)
ON CONFLICT DO NOTHING;

-- negotiation_lifecycle_record (DRAFT + SUBMITTED per copy, IDs 120–139)
INSERT INTO negotiation_lifecycle_record (id, creation_date, modified_date, created_by, modified_by, changed_to, negotiation_id)
VALUES
    (120, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440030'),
    (121, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440030'),
    (122, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440031'),
    (123, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440031'),
    (124, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440032'),
    (125, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440032'),
    (126, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440033'),
    (127, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440033'),
    (128, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440034'),
    (129, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440034'),
    (130, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440035'),
    (131, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440035'),
    (132, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440036'),
    (133, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440036'),
    (134, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440037'),
    (135, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440037'),
    (136, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440038'),
    (137, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440038'),
    (138, '2024-01-04 09:00:00', '2024-01-04 09:00:00', 1, 1, 'DRAFT',    '550e8400-e29b-41d4-a716-446655440039'),
    (139, '2024-01-04 09:01:00', '2024-01-04 09:01:00', 1, 1, 'SUBMITTED', '550e8400-e29b-41d4-a716-446655440039')
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('negotiation_lifecycle_record', 'id'), coalesce((SELECT max(id) FROM negotiation_lifecycle_record), 1));

-- negotiation_resource_link (resource_id=2 AND resource_id=3, SUBMITTED per copy)
INSERT INTO negotiation_resource_link (negotiation_id, resource_id, current_state)
VALUES
    ('550e8400-e29b-41d4-a716-446655440030', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440031', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440032', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440033', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440034', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440035', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440036', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440037', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440038', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440039', 2, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440030', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440031', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440032', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440033', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440034', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440035', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440036', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440037', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440038', 3, 'SUBMITTED'),
    ('550e8400-e29b-41d4-a716-446655440039', 3, 'SUBMITTED')
ON CONFLICT DO NOTHING;

-- negotiation_resource_lifecycle_record (CHECKING_AVAILABILITY per copy, IDs 120–129 for resource 2, 130–139 for resource 3)
INSERT INTO negotiation_resource_lifecycle_record (id, created_by, creation_date, modified_date, modified_by, changed_to, negotiation_id, resource_id)
VALUES
    (120, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440030', 2),
    (121, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440031', 2),
    (122, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440032', 2),
    (123, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440033', 2),
    (124, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440034', 2),
    (125, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440035', 2),
    (126, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440036', 2),
    (127, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440037', 2),
    (128, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440038', 2),
    (129, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440039', 2),
    (130, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440030', 3),
    (131, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440031', 3),
    (132, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440032', 3),
    (133, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440033', 3),
    (134, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440034', 3),
    (135, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440035', 3),
    (136, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440036', 3),
    (137, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440037', 3),
    (138, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440038', 3),
    (139, 2, '2024-01-04 11:00:00', '2024-01-04 11:00:00', 2, 'CHECKING_AVAILABILITY', '550e8400-e29b-41d4-a716-446655440039', 3)
ON CONFLICT DO NOTHING;

SELECT setval(pg_get_serial_sequence('negotiation_resource_lifecycle_record', 'id'), coalesce((SELECT max(id) FROM negotiation_resource_lifecycle_record), 1));

-- post (1 PUBLIC post per negotiation copy)
INSERT INTO post (id, creation_date, modified_date, created_by, modified_by, type, text, negotiation_id)
VALUES
    ('550e8400-e29b-41d4-a716-556655440030', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440030'),
    ('550e8400-e29b-41d4-a716-556655440031', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440031'),
    ('550e8400-e29b-41d4-a716-556655440032', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440032'),
    ('550e8400-e29b-41d4-a716-556655440033', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440033'),
    ('550e8400-e29b-41d4-a716-556655440034', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440034'),
    ('550e8400-e29b-41d4-a716-556655440035', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440035'),
    ('550e8400-e29b-41d4-a716-556655440036', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440036'),
    ('550e8400-e29b-41d4-a716-556655440037', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440037'),
    ('550e8400-e29b-41d4-a716-556655440038', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440038'),
    ('550e8400-e29b-41d4-a716-556655440039', '2024-01-04 11:15:00', '2024-01-04 11:15:00', 2, 2, 'PUBLIC', 'We are checking availability locally for this study. Our biobank is reviewing sample availability and will provide feedback shortly.', '550e8400-e29b-41d4-a716-446655440039')
ON CONFLICT DO NOTHING;