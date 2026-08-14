CREATE TABLE telemetry_batch (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    reading_count SMALLINT NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_telemetry_batch PRIMARY KEY (id),
    CONSTRAINT fk_telemetry_batch_organisation FOREIGN KEY (organisation_id)
        REFERENCES organisation (id) ON DELETE RESTRICT,
    CONSTRAINT uq_telemetry_batch_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_telemetry_batch_organisation_key UNIQUE (
        organisation_id,
        idempotency_key
    ),
    CONSTRAINT ck_telemetry_batch_idempotency_key CHECK (
        idempotency_key = BTRIM(idempotency_key)
        AND idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$'
    ),
    CONSTRAINT ck_telemetry_batch_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_telemetry_batch_reading_count CHECK (
        reading_count BETWEEN 1 AND 100
    )
);

CREATE TABLE telemetry_reading (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    sequence_number SMALLINT NOT NULL,
    sensor_id UUID NOT NULL,
    value NUMERIC(19, 6) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_telemetry_reading PRIMARY KEY (id),
    CONSTRAINT fk_telemetry_reading_batch FOREIGN KEY (organisation_id, batch_id)
        REFERENCES telemetry_batch (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_telemetry_reading_sensor FOREIGN KEY (organisation_id, sensor_id)
        REFERENCES sensor (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_telemetry_reading_batch_sequence UNIQUE (batch_id, sequence_number),
    CONSTRAINT ck_telemetry_reading_sequence CHECK (
        sequence_number BETWEEN 0 AND 99
    ),
    CONSTRAINT ck_telemetry_reading_value CHECK (
        value BETWEEN -1000000000000.000000 AND 1000000000000.000000
    )
);
