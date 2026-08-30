package io.github.moar0210.assetpulse.audit;

public record AuditListRequest(int limit) {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    public AuditListRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidAuditQueryException();
        }
    }

    public static AuditListRequest fromQuery(String limitValue) {
        if (limitValue == null) {
            return new AuditListRequest(DEFAULT_LIMIT);
        }
        if (!limitValue.matches("[1-9][0-9]{0,2}")) {
            throw new InvalidAuditQueryException();
        }
        return new AuditListRequest(Integer.parseInt(limitValue));
    }
}
