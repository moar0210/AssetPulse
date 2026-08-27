package io.github.moar0210.assetpulse.workorders;

public record WorkOrderListRequest(int limit) {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    public WorkOrderListRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidWorkOrderQueryException();
        }
    }

    public static WorkOrderListRequest fromQuery(String limitValue) {
        if (limitValue == null) {
            return new WorkOrderListRequest(DEFAULT_LIMIT);
        }
        if (!limitValue.matches("[1-9][0-9]{0,2}")) {
            throw new InvalidWorkOrderQueryException();
        }

        try {
            return new WorkOrderListRequest(Integer.parseInt(limitValue));
        } catch (NumberFormatException exception) {
            throw new InvalidWorkOrderQueryException();
        }
    }
}
