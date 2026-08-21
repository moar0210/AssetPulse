CREATE TABLE telemetry_processing_event (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    telemetry_batch_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_telemetry_processing_event PRIMARY KEY (id),
    CONSTRAINT fk_telemetry_processing_event_batch FOREIGN KEY (
        organisation_id,
        telemetry_batch_id
    ) REFERENCES telemetry_batch (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_telemetry_processing_event_organisation_batch UNIQUE (
        organisation_id,
        telemetry_batch_id
    ),
    CONSTRAINT ck_telemetry_processing_event_type CHECK (
        event_type = 'TELEMETRY_BATCH_ACCEPTED'
    )
);
