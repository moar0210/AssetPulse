package io.github.moar0210.assetpulse.telemetry;

public class InvalidSensorReferenceException extends RuntimeException {

    public InvalidSensorReferenceException() {
        super("One or more sensors do not exist or are not accessible");
    }
}
