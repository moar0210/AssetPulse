CREATE TABLE alert (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    threshold_rule_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    first_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cooldown_until TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_alert PRIMARY KEY (id),
    CONSTRAINT fk_alert_organisation FOREIGN KEY (organisation_id)
        REFERENCES organisation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alert_threshold_rule FOREIGN KEY (organisation_id, threshold_rule_id)
        REFERENCES threshold_rule (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_alert_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_alert_organisation_rule UNIQUE (organisation_id, threshold_rule_id),
    CONSTRAINT uq_alert_organisation_fingerprint UNIQUE (organisation_id, fingerprint),
    CONSTRAINT ck_alert_fingerprint CHECK (
        fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_alert_status CHECK (status = 'OPEN'),
    CONSTRAINT ck_alert_occurrence_count CHECK (occurrence_count >= 1),
    CONSTRAINT ck_alert_occurrence_timestamps CHECK (
        first_occurred_at <= last_occurred_at
        AND last_occurred_at <= cooldown_until
    ),
    CONSTRAINT ck_alert_record_timestamps CHECK (created_at <= updated_at)
);

CREATE INDEX ix_alert_organisation_status_last_occurred_id
    ON alert (organisation_id, status, last_occurred_at DESC, id);
