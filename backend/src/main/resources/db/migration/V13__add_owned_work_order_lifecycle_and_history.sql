ALTER TABLE work_order
    DROP CONSTRAINT ck_work_order_status,
    DROP CONSTRAINT ck_work_order_assignment_consistency,
    ADD CONSTRAINT ck_work_order_status CHECK (
        status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'DONE')
    ),
    ADD CONSTRAINT ck_work_order_assignment_consistency CHECK (
        (
            status = 'OPEN'
            AND version = 0
            AND assigned_technician_user_id IS NULL
            AND assigned_technician_role_code IS NULL
            AND assigned_at IS NULL
        )
        OR (
            (
                (status = 'ASSIGNED' AND version = 1)
                OR (status = 'IN_PROGRESS' AND version = 2)
                OR (status = 'DONE' AND version = 3)
            )
            AND assigned_technician_user_id IS NOT NULL
            AND assigned_technician_role_code IS NOT NULL
            AND assigned_technician_role_code = 'TECHNICIAN'
            AND assigned_at IS NOT NULL
            AND created_at <= assigned_at
            AND assigned_at <= updated_at
        )
    );

CREATE FUNCTION reject_work_order_assignment_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.assigned_at IS NOT NULL AND (
        NEW.assigned_technician_user_id IS DISTINCT FROM OLD.assigned_technician_user_id
        OR NEW.assigned_technician_role_code IS DISTINCT FROM OLD.assigned_technician_role_code
        OR NEW.assigned_at IS DISTINCT FROM OLD.assigned_at
    ) THEN
        RAISE EXCEPTION 'Work-order assignment is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_work_order_assignment_immutable
    BEFORE UPDATE OF assigned_technician_user_id, assigned_technician_role_code, assigned_at
    ON work_order
    FOR EACH ROW
    EXECUTE FUNCTION reject_work_order_assignment_change();

CREATE TABLE work_order_status_history (
    organisation_id UUID NOT NULL,
    work_order_id UUID NOT NULL,
    sequence_number SMALLINT NOT NULL,
    from_status VARCHAR(16) NOT NULL,
    to_status VARCHAR(16) NOT NULL,
    actor_user_id UUID,
    transitioned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_work_order_status_history PRIMARY KEY (
        organisation_id,
        work_order_id,
        sequence_number
    ),
    CONSTRAINT fk_work_order_status_history_work_order FOREIGN KEY (
        organisation_id,
        work_order_id
    ) REFERENCES work_order (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_work_order_status_history_actor FOREIGN KEY (organisation_id, actor_user_id)
        REFERENCES app_user (organisation_id, id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_work_order_status_history_transition CHECK (
        (sequence_number = 1 AND from_status = 'OPEN' AND to_status = 'ASSIGNED')
        OR (sequence_number = 2 AND from_status = 'ASSIGNED' AND to_status = 'IN_PROGRESS')
        OR (sequence_number = 3 AND from_status = 'IN_PROGRESS' AND to_status = 'DONE')
    ),
    CONSTRAINT ck_work_order_status_history_legacy_actor CHECK (
        actor_user_id IS NOT NULL OR sequence_number = 1
    )
);

-- V12 retained assignment time but did not retain the assigning administrator.
INSERT INTO work_order_status_history (
    organisation_id,
    work_order_id,
    sequence_number,
    from_status,
    to_status,
    actor_user_id,
    transitioned_at
)
SELECT organisation_id, id, 1, 'OPEN', 'ASSIGNED', NULL, assigned_at
FROM work_order
WHERE status = 'ASSIGNED';

CREATE FUNCTION validate_work_order_status_history_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_work_order work_order%ROWTYPE;
    previous_transition_at TIMESTAMP WITH TIME ZONE;
BEGIN
    IF NEW.actor_user_id IS NULL THEN
        RAISE EXCEPTION 'New work-order history requires an actor'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO current_work_order
    FROM work_order
    WHERE organisation_id = NEW.organisation_id AND id = NEW.work_order_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Work-order history requires a matching work order'
            USING ERRCODE = '23503';
    END IF;
    IF NEW.sequence_number <> current_work_order.version
        OR NEW.to_status <> current_work_order.status
        OR NEW.transitioned_at <> current_work_order.updated_at
        OR NEW.transitioned_at < current_work_order.created_at THEN
        RAISE EXCEPTION 'Work-order history must match its current state'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.sequence_number = 1 THEN
        IF NEW.transitioned_at <> current_work_order.assigned_at THEN
            RAISE EXCEPTION 'Assignment history must match the assignment time'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT transitioned_at INTO previous_transition_at
        FROM work_order_status_history
        WHERE organisation_id = NEW.organisation_id
          AND work_order_id = NEW.work_order_id
          AND sequence_number = NEW.sequence_number - 1;

        IF NOT FOUND OR NEW.transitioned_at < previous_transition_at THEN
            RAISE EXCEPTION 'Work-order history must be contiguous and chronological'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_work_order_status_history_insert
    BEFORE INSERT ON work_order_status_history
    FOR EACH ROW
    EXECUTE FUNCTION validate_work_order_status_history_insert();

CREATE FUNCTION reject_work_order_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Work-order status history is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER tr_work_order_status_history_immutable
    BEFORE UPDATE OR DELETE ON work_order_status_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_work_order_status_history_mutation();
