package io.github.moar0210.assetpulse.alerts;

public record AlertListRequest(int limit) {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    public AlertListRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidAlertQueryException();
        }
    }

    public static AlertListRequest fromQuery(String limitValue) {
        if (limitValue == null) {
            return new AlertListRequest(DEFAULT_LIMIT);
        }
        if (!limitValue.matches("[1-9][0-9]{0,2}")) {
            throw new InvalidAlertQueryException();
        }

        int parsedLimit;
        try {
            parsedLimit = Integer.parseInt(limitValue);
        } catch (NumberFormatException exception) {
            throw new InvalidAlertQueryException();
        }
        return new AlertListRequest(parsedLimit);
    }
}
