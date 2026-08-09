CREATE TABLE organisation (
    id UUID NOT NULL,
    slug VARCHAR(63) NOT NULL,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_organisation PRIMARY KEY (id),
    CONSTRAINT uq_organisation_slug UNIQUE (slug),
    CONSTRAINT ck_organisation_slug_format CHECK (
        slug = LOWER(BTRIM(slug))
        AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT ck_organisation_name CHECK (
        name = BTRIM(name)
        AND name <> ''
    ),
    CONSTRAINT ck_organisation_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE app_role (
    code VARCHAR(32) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_app_role PRIMARY KEY (code),
    CONSTRAINT uq_app_role_display_name UNIQUE (display_name),
    CONSTRAINT ck_app_role_code CHECK (
        code IN ('OPERATIONS_ADMIN', 'TECHNICIAN', 'VIEWER')
    ),
    CONSTRAINT ck_app_role_display_name CHECK (
        display_name = BTRIM(display_name)
        AND display_name <> ''
    ),
    CONSTRAINT ck_app_role_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE app_user (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    email VARCHAR(254) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT fk_app_user_organisation FOREIGN KEY (organisation_id)
        REFERENCES organisation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_app_user_role FOREIGN KEY (role_code)
        REFERENCES app_role (code) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email CHECK (
        email = LOWER(BTRIM(email))
        AND email <> ''
    ),
    CONSTRAINT ck_app_user_display_name CHECK (
        display_name = BTRIM(display_name)
        AND display_name <> ''
    ),
    CONSTRAINT ck_app_user_password_hash CHECK (
        password_hash = BTRIM(password_hash)
        AND password_hash <> ''
    ),
    CONSTRAINT ck_app_user_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE asset (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    asset_code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_asset PRIMARY KEY (id),
    CONSTRAINT fk_asset_organisation FOREIGN KEY (organisation_id)
        REFERENCES organisation (id) ON DELETE RESTRICT,
    CONSTRAINT uq_asset_organisation_code UNIQUE (organisation_id, asset_code),
    CONSTRAINT ck_asset_code CHECK (
        asset_code = UPPER(BTRIM(asset_code))
        AND asset_code <> ''
    ),
    CONSTRAINT ck_asset_name CHECK (
        name = BTRIM(name)
        AND name <> ''
    ),
    CONSTRAINT ck_asset_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_asset_organisation_name_id
    ON asset (organisation_id, name, id);
