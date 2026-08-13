ALTER TABLE asset
    ADD CONSTRAINT uq_asset_organisation_id UNIQUE (organisation_id, id);

CREATE TABLE sensor (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    asset_id UUID NOT NULL,
    sensor_key VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    measurement_type VARCHAR(32) NOT NULL,
    unit VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sensor PRIMARY KEY (id),
    CONSTRAINT fk_sensor_asset FOREIGN KEY (organisation_id, asset_id)
        REFERENCES asset (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_sensor_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_sensor_organisation_key UNIQUE (organisation_id, sensor_key),
    CONSTRAINT ck_sensor_key CHECK (
        sensor_key = UPPER(BTRIM(sensor_key))
        AND sensor_key ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$'
    ),
    CONSTRAINT ck_sensor_name CHECK (
        name = BTRIM(name)
        AND name <> ''
    ),
    CONSTRAINT ck_sensor_measurement_type CHECK (
        measurement_type IN ('TEMPERATURE')
    ),
    CONSTRAINT ck_sensor_unit CHECK (
        unit IN ('CELSIUS')
    ),
    CONSTRAINT ck_sensor_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_sensor_organisation_asset_name_id
    ON sensor (organisation_id, asset_id, name, id);

CREATE TABLE threshold_rule (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    sensor_id UUID NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    comparison VARCHAR(32) NOT NULL,
    threshold_value NUMERIC(19, 6) NOT NULL,
    cooldown_seconds INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_threshold_rule PRIMARY KEY (id),
    CONSTRAINT fk_threshold_rule_sensor FOREIGN KEY (organisation_id, sensor_id)
        REFERENCES sensor (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_threshold_rule_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_threshold_rule_organisation_code UNIQUE (organisation_id, rule_code),
    CONSTRAINT ck_threshold_rule_code CHECK (
        rule_code = UPPER(BTRIM(rule_code))
        AND rule_code ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$'
    ),
    CONSTRAINT ck_threshold_rule_name CHECK (
        name = BTRIM(name)
        AND name <> ''
    ),
    CONSTRAINT ck_threshold_rule_comparison CHECK (
        comparison IN ('GREATER_THAN_OR_EQUAL_TO')
    ),
    CONSTRAINT ck_threshold_rule_value CHECK (
        threshold_value BETWEEN -1000000000000.000000 AND 1000000000000.000000
    ),
    CONSTRAINT ck_threshold_rule_cooldown CHECK (
        cooldown_seconds BETWEEN 0 AND 604800
    ),
    CONSTRAINT ck_threshold_rule_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_threshold_rule_organisation_sensor_name_id
    ON threshold_rule (organisation_id, sensor_id, name, id);
