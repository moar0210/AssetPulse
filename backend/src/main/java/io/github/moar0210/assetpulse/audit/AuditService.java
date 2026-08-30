package io.github.moar0210.assetpulse.audit;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(
            UUID organisationId,
            UUID actorUserId,
            AuditAction action,
            UUID subjectId,
            String correlationId) {
        Objects.requireNonNull(organisationId);
        Objects.requireNonNull(actorUserId);
        Objects.requireNonNull(subjectId);
        if (action == null || action == AuditAction.AUTHENTICATION_FAILED) {
            throw new IllegalArgumentException("A scoped audit event requires an admitted action");
        }
        repository.insert(
                organisationId, actorUserId, action, subjectId, parseCorrelationId(correlationId));
    }

    @Transactional
    public void recordAuthenticationFailure(String correlationId) {
        repository.insert(
                null,
                null,
                AuditAction.AUTHENTICATION_FAILED,
                null,
                parseCorrelationId(correlationId));
    }

    private static UUID parseCorrelationId(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Audit correlation ID must be a canonical UUID");
        }
        return parsed;
    }
}
