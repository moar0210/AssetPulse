ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_organisation_id_role UNIQUE (
        organisation_id,
        id,
        role_code
    );

CREATE TABLE work_order (
    id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    alert_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    assigned_technician_user_id UUID,
    assigned_technician_role_code VARCHAR(32),
    assigned_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_work_order PRIMARY KEY (id),
    CONSTRAINT fk_work_order_alert FOREIGN KEY (organisation_id, alert_id)
        REFERENCES alert (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_work_order_assigned_technician FOREIGN KEY (
        organisation_id,
        assigned_technician_user_id,
        assigned_technician_role_code
    ) REFERENCES app_user (organisation_id, id, role_code)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT uq_work_order_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_work_order_organisation_alert UNIQUE (organisation_id, alert_id),
    CONSTRAINT ck_work_order_status CHECK (status IN ('OPEN', 'ASSIGNED')),
    CONSTRAINT ck_work_order_version CHECK (version >= 0),
    CONSTRAINT ck_work_order_assignment_consistency CHECK (
        (
            status = 'OPEN'
            AND version = 0
            AND assigned_technician_user_id IS NULL
            AND assigned_technician_role_code IS NULL
            AND assigned_at IS NULL
        )
        OR (
            status = 'ASSIGNED'
            AND version = 1
            AND assigned_technician_user_id IS NOT NULL
            AND assigned_technician_role_code = 'TECHNICIAN'
            AND assigned_at IS NOT NULL
            AND created_at <= assigned_at
            AND assigned_at <= updated_at
        )
    ),
    CONSTRAINT ck_work_order_timestamps CHECK (created_at <= updated_at)
);

CREATE INDEX ix_work_order_organisation_updated_id
    ON work_order (organisation_id, updated_at DESC, id);

CREATE INDEX ix_work_order_organisation_assignee_updated_id
    ON work_order (
        organisation_id,
        assigned_technician_user_id,
        updated_at DESC,
        id
    )
    WHERE assigned_technician_user_id IS NOT NULL;

CREATE FUNCTION reject_work_order_scope_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.organisation_id IS DISTINCT FROM OLD.organisation_id
        OR NEW.alert_id IS DISTINCT FROM OLD.alert_id THEN
        RAISE EXCEPTION 'Work-order organisation and alert link are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_work_order_scope_immutable
    BEFORE UPDATE OF organisation_id, alert_id ON work_order
    FOR EACH ROW
    EXECUTE FUNCTION reject_work_order_scope_change();
