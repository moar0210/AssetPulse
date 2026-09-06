package io.github.moar0210.assetpulse.audit;

public enum AuditAction {
    AUTHENTICATION_SUCCEEDED(AuditSubjectType.USER),
    AUTHENTICATION_FAILED(null),
    SESSION_ENDED(AuditSubjectType.USER),
    ALERT_ACKNOWLEDGED(AuditSubjectType.ALERT),
    ALERT_RESOLVED(AuditSubjectType.ALERT),
    WORK_ORDER_CREATED(AuditSubjectType.WORK_ORDER),
    WORK_ORDER_ASSIGNED(AuditSubjectType.WORK_ORDER),
    WORK_ORDER_STARTED(AuditSubjectType.WORK_ORDER),
    WORK_ORDER_COMPLETED(AuditSubjectType.WORK_ORDER),
    PROCESSING_EVENT_RETRIED(AuditSubjectType.PROCESSING_EVENT),
    DEMO_RESET(AuditSubjectType.USER);

    private final AuditSubjectType subjectType;

    AuditAction(AuditSubjectType subjectType) {
        this.subjectType = subjectType;
    }

    public AuditSubjectType subjectType() {
        return subjectType;
    }
}
