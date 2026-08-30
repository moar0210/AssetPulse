ALTER TABLE telemetry_processing_event
    ADD CONSTRAINT uq_telemetry_processing_event_organisation_id UNIQUE (organisation_id, id);

CREATE TABLE audit_event (
    id UUID NOT NULL,
    organisation_id UUID,
    actor_user_id UUID,
    action VARCHAR(40) NOT NULL,
    subject_user_id UUID,
    subject_alert_id UUID,
    subject_work_order_id UUID,
    subject_processing_event_id UUID,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    correlation_id UUID NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT ck_audit_event_time CHECK (isfinite(occurred_at)),
    CONSTRAINT fk_audit_event_organisation FOREIGN KEY (organisation_id)
        REFERENCES organisation (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (organisation_id, actor_user_id)
        REFERENCES app_user (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_subject_user FOREIGN KEY (organisation_id, subject_user_id)
        REFERENCES app_user (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_subject_alert FOREIGN KEY (organisation_id, subject_alert_id)
        REFERENCES alert (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_subject_work_order FOREIGN KEY (organisation_id, subject_work_order_id)
        REFERENCES work_order (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_audit_event_subject_processing_event FOREIGN KEY (
        organisation_id, subject_processing_event_id
    ) REFERENCES telemetry_processing_event (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_audit_event_shape CHECK (
        (
            action = 'AUTHENTICATION_FAILED'
            AND organisation_id IS NULL
            AND actor_user_id IS NULL
            AND num_nonnulls(subject_user_id, subject_alert_id,
                subject_work_order_id, subject_processing_event_id) = 0
        )
        OR (
            organisation_id IS NOT NULL
            AND actor_user_id IS NOT NULL
            AND num_nonnulls(subject_user_id, subject_alert_id,
                subject_work_order_id, subject_processing_event_id) = 1
            AND (
                (
                    action IN ('AUTHENTICATION_SUCCEEDED', 'SESSION_ENDED')
                    AND subject_user_id IS NOT NULL
                    AND subject_user_id = actor_user_id
                )
                OR (
                    action IN ('ALERT_ACKNOWLEDGED', 'ALERT_RESOLVED')
                    AND subject_alert_id IS NOT NULL
                )
                OR (
                    action IN ('WORK_ORDER_CREATED', 'WORK_ORDER_ASSIGNED',
                        'WORK_ORDER_STARTED', 'WORK_ORDER_COMPLETED')
                    AND subject_work_order_id IS NOT NULL
                )
                OR (
                    action = 'PROCESSING_EVENT_RETRIED'
                    AND subject_processing_event_id IS NOT NULL
                )
            )
        )
    )
);

CREATE INDEX ix_audit_event_organisation_time_id
    ON audit_event (organisation_id, occurred_at DESC, id DESC)
    WHERE organisation_id IS NOT NULL;

CREATE FUNCTION reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Audit events are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW
    EXECUTE FUNCTION reject_audit_event_mutation();
