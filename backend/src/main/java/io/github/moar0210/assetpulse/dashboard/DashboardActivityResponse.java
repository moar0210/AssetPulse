package io.github.moar0210.assetpulse.dashboard;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditSubjectType;
import java.time.Instant;
import java.util.UUID;

public record DashboardActivityResponse(
        AuditAction action, AuditSubjectType subjectType, UUID subjectId, Instant occurredAt) {}
