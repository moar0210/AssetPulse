package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryProcessingExecutionService {

    private final TelemetryProcessingEventRepository repository;

    public TelemetryProcessingExecutionService(TelemetryProcessingEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean execute(
            TelemetryProcessingClaim claim, TelemetryProcessingEventHandler handler) {
        Objects.requireNonNull(claim);
        Objects.requireNonNull(handler);

        return repository
                .lockClaim(claim.event().id(), claim.claimToken())
                .map(
                        event -> {
                            handler.handle(event);
                            if (!repository.complete(
                                    event.id(), claim.claimToken(), Instant.now())) {
                                throw new IllegalStateException(
                                        "Current telemetry processing claim could not be completed");
                            }
                            return true;
                        })
                .orElse(false);
    }
}
