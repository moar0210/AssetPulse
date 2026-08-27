package io.github.moar0210.assetpulse.telemetry;

public record DeadProcessingEventListRequest(int limit) {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    public DeadProcessingEventListRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidProcessingEventQueryException();
        }
    }

    public static DeadProcessingEventListRequest fromQuery(String limitValue) {
        if (limitValue == null) {
            return new DeadProcessingEventListRequest(DEFAULT_LIMIT);
        }
        if (!limitValue.matches("[1-9][0-9]{0,2}")) {
            throw new InvalidProcessingEventQueryException();
        }

        int parsedLimit;
        try {
            parsedLimit = Integer.parseInt(limitValue);
        } catch (NumberFormatException exception) {
            throw new InvalidProcessingEventQueryException();
        }
        return new DeadProcessingEventListRequest(parsedLimit);
    }
}
