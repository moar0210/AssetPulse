CREATE INDEX ix_telemetry_reading_organisation_sensor_observed_id
    ON telemetry_reading (
        organisation_id,
        sensor_id,
        observed_at DESC,
        id DESC
    )
    INCLUDE (value);
