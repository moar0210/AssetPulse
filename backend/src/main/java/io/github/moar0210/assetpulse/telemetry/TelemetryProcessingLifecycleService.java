package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryProcessingLifecycleService {

    static final String PROCESSING_FAILURE_CODE = "PROCESSING_FAILED";
    static final String PROCESSING_FAILURE_MESSAGE =
            "Processing failed; another attempt may be scheduled.";
    static final String ABANDONED_FAILURE_CODE = "LEASE_EXPIRED";
    static final String ABANDONED_FAILURE_MESSAGE =
            "Processing lease expired after the final attempt.";

    private final TelemetryProcessingEventRepository repository;
    private final TelemetryProcessingPolicy policy;

    public TelemetryProcessingLifecycleService(
            TelemetryProcessingEventRepository repository, TelemetryProcessingPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    @Transactional
    public Optional<TelemetryProcessingClaim> claimNext(String claimOwner, Instant now) {
        requireClaimOwner(claimOwner);
        Objects.requireNonNull(now);

        repository.moveExhaustedExpiredLeasesToDead(
                now, policy.maxAttempts(), ABANDONED_FAILURE_CODE, ABANDONED_FAILURE_MESSAGE);
        return repository.claimNext(
                claimOwner,
                UUID.randomUUID(),
                now,
                now.plus(policy.leaseDuration()),
                policy.maxAttempts());
    }

    @Transactional
    public TelemetryProcessingFailureDisposition recordFailure(
            TelemetryProcessingClaim claim, Instant now) {
        Objects.requireNonNull(claim);
        Objects.requireNonNull(now);

        Instant nextAttemptAt =
                claim.attemptCount() >= policy.maxAttempts()
                        ? null
                        : now.plus(policy.retryDelay(claim.attemptCount()));
        return repository
                .recordFailure(
                        claim.event().id(),
                        claim.claimToken(),
                        now,
                        nextAttemptAt,
                        policy.maxAttempts(),
                        PROCESSING_FAILURE_CODE,
                        PROCESSING_FAILURE_MESSAGE)
                .map(TelemetryProcessingFailureDisposition::valueOf)
                .orElse(TelemetryProcessingFailureDisposition.STALE_CLAIM);
    }

    private void requireClaimOwner(String claimOwner) {
        if (claimOwner == null
                || claimOwner.isBlank()
                || !claimOwner.equals(claimOwner.trim())
                || claimOwner.length() > 128) {
            throw new IllegalArgumentException("Claim owner must be 1 to 128 trimmed characters");
        }
    }
}
