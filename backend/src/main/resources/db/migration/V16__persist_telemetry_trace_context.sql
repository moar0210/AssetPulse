ALTER TABLE telemetry_processing_event
    ADD COLUMN trace_parent VARCHAR(55),
    ADD COLUMN trace_state VARCHAR(512),
    ADD CONSTRAINT ck_telemetry_processing_event_trace_parent CHECK (
        trace_parent IS NULL
        OR (
            trace_parent ~ '^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'
            AND SUBSTRING(trace_parent FROM 4 FOR 32) <> REPEAT('0', 32)
            AND SUBSTRING(trace_parent FROM 37 FOR 16) <> REPEAT('0', 16)
        )
    ),
    ADD CONSTRAINT ck_telemetry_processing_event_trace_state CHECK (
        trace_state IS NULL
        OR (
            trace_parent IS NOT NULL
            AND BTRIM(trace_state) <> ''
        )
    );
