package io.github.moar0210.assetpulse.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        AuditActorResponse actor,
        AuditAction action,
        AuditSubjectResponse subject,
        Instant occurredAt,
        UUID correlationId) {}
