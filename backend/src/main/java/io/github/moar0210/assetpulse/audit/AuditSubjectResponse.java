package io.github.moar0210.assetpulse.audit;

import java.util.UUID;

public record AuditSubjectResponse(AuditSubjectType type, UUID id) {}
