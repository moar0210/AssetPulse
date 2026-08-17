package io.github.moar0210.assetpulse.telemetry;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryReadingService {

    private final TelemetryReadingRepository repository;

    public TelemetryReadingService(TelemetryReadingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public TelemetryReadingRangeResponse readRange(
            UUID organisationId, UUID sensorId, TelemetryReadingRangeRequest range) {
        if (!repository.sensorExistsByOrganisationIdAndId(organisationId, sensorId)) {
            throw new SensorNotFoundException();
        }

        return new TelemetryReadingRangeResponse(
                sensorId,
                range.from(),
                range.to(),
                repository.findMostRecentInRange(
                        organisationId, sensorId, range.from(), range.to(), range.limit()));
    }
}
