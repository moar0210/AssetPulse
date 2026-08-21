ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_organisation_id UNIQUE (organisation_id, id);

ALTER TABLE alert
    DROP CONSTRAINT ck_alert_status,
    DROP CONSTRAINT uq_alert_organisation_rule,
    DROP CONSTRAINT uq_alert_organisation_fingerprint,
    ADD CONSTRAINT ck_alert_status CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')
    );

CREATE UNIQUE INDEX uq_alert_organisation_rule
    ON alert (organisation_id, threshold_rule_id)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

CREATE UNIQUE INDEX uq_alert_organisation_fingerprint
    ON alert (organisation_id, fingerprint)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

CREATE TABLE alert_status_history (
    organisation_id UUID NOT NULL,
    alert_id UUID NOT NULL,
    sequence_number SMALLINT NOT NULL,
    from_status VARCHAR(16) NOT NULL,
    to_status VARCHAR(16) NOT NULL,
    actor_user_id UUID NOT NULL,
    transitioned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_alert_status_history PRIMARY KEY (
        organisation_id,
        alert_id,
        sequence_number
    ),
    CONSTRAINT fk_alert_status_history_alert FOREIGN KEY (organisation_id, alert_id)
        REFERENCES alert (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_alert_status_history_actor FOREIGN KEY (organisation_id, actor_user_id)
        REFERENCES app_user (organisation_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_alert_status_history_transition CHECK (
        (
            sequence_number = 1
            AND from_status = 'OPEN'
            AND to_status = 'ACKNOWLEDGED'
        )
        OR (
            sequence_number = 2
            AND from_status = 'ACKNOWLEDGED'
            AND to_status = 'RESOLVED'
        )
    )
);

CREATE FUNCTION reject_alert_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Alert status history is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_alert_status_history_immutable
    BEFORE UPDATE OR DELETE ON alert_status_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_alert_status_history_mutation();
