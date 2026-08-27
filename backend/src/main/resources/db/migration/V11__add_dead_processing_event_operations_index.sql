CREATE INDEX ix_telemetry_processing_event_dead_organisation_time_id
    ON telemetry_processing_event (organisation_id, dead_at DESC, id)
    WHERE status = 'DEAD';
