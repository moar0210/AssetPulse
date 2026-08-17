ALTER TABLE telemetry_processing_event
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempt_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN claim_token UUID,
    ADD COLUMN claim_owner VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN dead_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_error_code VARCHAR(64),
    ADD COLUMN last_error_message VARCHAR(256),
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

UPDATE telemetry_processing_event
SET next_attempt_at = created_at,
    updated_at = created_at;

ALTER TABLE telemetry_processing_event
    ALTER COLUMN next_attempt_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT ck_telemetry_processing_event_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')
    ),
    ADD CONSTRAINT ck_telemetry_processing_event_attempt_count CHECK (
        attempt_count BETWEEN 0 AND 5
    ),
    ADD CONSTRAINT ck_telemetry_processing_event_claim_owner CHECK (
        claim_owner IS NULL OR BTRIM(claim_owner) <> ''
    ),
    ADD CONSTRAINT ck_telemetry_processing_event_error_fields CHECK (
        (
            last_error_code IS NULL
            AND last_error_message IS NULL
        )
        OR (
            last_error_code IS NOT NULL
            AND BTRIM(last_error_code) <> ''
            AND last_error_message IS NOT NULL
            AND BTRIM(last_error_message) <> ''
        )
    ),
    ADD CONSTRAINT ck_telemetry_processing_event_state_consistency CHECK (
        (
            status = 'PENDING'
            AND attempt_count BETWEEN 0 AND 4
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_owner IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NULL
            AND dead_at IS NULL
            AND (
                (
                    attempt_count = 0
                    AND last_error_code IS NULL
                    AND last_error_message IS NULL
                )
                OR (
                    attempt_count BETWEEN 1 AND 4
                    AND last_error_code IS NOT NULL
                    AND last_error_message IS NOT NULL
                )
            )
        )
        OR (
            status = 'PROCESSING'
            AND attempt_count BETWEEN 1 AND 5
            AND next_attempt_at IS NULL
            AND claim_token IS NOT NULL
            AND claim_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND completed_at IS NULL
            AND dead_at IS NULL
            AND last_error_code IS NULL
            AND last_error_message IS NULL
        )
        OR (
            status = 'COMPLETED'
            AND attempt_count BETWEEN 1 AND 5
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_owner IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NOT NULL
            AND dead_at IS NULL
            AND last_error_code IS NULL
            AND last_error_message IS NULL
        )
        OR (
            status = 'DEAD'
            AND attempt_count = 5
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_owner IS NULL
            AND lease_expires_at IS NULL
            AND completed_at IS NULL
            AND dead_at IS NOT NULL
            AND last_error_code IS NOT NULL
            AND last_error_message IS NOT NULL
        )
    );

CREATE INDEX ix_telemetry_processing_event_due_work
    ON telemetry_processing_event (next_attempt_at, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX ix_telemetry_processing_event_expired_lease
    ON telemetry_processing_event (lease_expires_at, created_at, id)
    WHERE status = 'PROCESSING';
