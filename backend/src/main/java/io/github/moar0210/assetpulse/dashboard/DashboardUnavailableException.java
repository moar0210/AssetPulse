package io.github.moar0210.assetpulse.dashboard;

public class DashboardUnavailableException extends RuntimeException {

    public DashboardUnavailableException(Throwable cause) {
        super("Dashboard unavailable", cause);
    }
}
