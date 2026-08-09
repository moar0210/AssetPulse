INSERT INTO organisation (id, slug, name, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'northstar-operations', 'Northstar Operations', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('00000000-0000-0000-0000-000000000002', 'riverside-manufacturing', 'Riverside Manufacturing', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00');

INSERT INTO app_role (code, display_name, created_at, updated_at)
VALUES
    ('OPERATIONS_ADMIN', 'Operations Admin', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('TECHNICIAN', 'Technician', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('VIEWER', 'Viewer', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00');

INSERT INTO app_user (
    id,
    organisation_id,
    email,
    display_name,
    password_hash,
    role_code,
    created_at,
    updated_at
)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'admin@northstar.example', 'Nora Admin', '{bcrypt}$2b$12$jSV61.ogKjcT7XBtCi2gRugGPvHukSHhPjQbaCXs6Ej2/j9/daHxy', 'OPERATIONS_ADMIN', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'technician@northstar.example', 'Theo Technician', '{bcrypt}$2b$12$jSV61.ogKjcT7XBtCi2gRugGPvHukSHhPjQbaCXs6Ej2/j9/daHxy', 'TECHNICIAN', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'viewer@northstar.example', 'Vera Viewer', '{bcrypt}$2b$12$jSV61.ogKjcT7XBtCi2gRugGPvHukSHhPjQbaCXs6Ej2/j9/daHxy', 'VIEWER', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002', 'admin@riverside.example', 'Riley Admin', '{bcrypt}$2b$12$jSV61.ogKjcT7XBtCi2gRugGPvHukSHhPjQbaCXs6Ej2/j9/daHxy', 'OPERATIONS_ADMIN', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00');

INSERT INTO asset (
    id,
    organisation_id,
    asset_code,
    name,
    created_at,
    updated_at
)
VALUES
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'PUMP-101', 'Boiler Feed Pump', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'PUMP-102', 'Cooling Water Pump', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00'),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'PUMP-201', 'Process Pump', '2026-07-29 00:00:00+00', '2026-07-29 00:00:00+00');
