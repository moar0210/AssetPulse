ALTER TABLE audit_event
    DROP CONSTRAINT ck_audit_event_shape,
    ADD CONSTRAINT ck_audit_event_shape CHECK (
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
                    action IN ('AUTHENTICATION_SUCCEEDED', 'SESSION_ENDED', 'DEMO_RESET')
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
    );
