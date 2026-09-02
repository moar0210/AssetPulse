package io.github.moar0210.assetpulse.demo;

public class DemoResetUnavailableException extends RuntimeException {

    public DemoResetUnavailableException() {
        super("Demo reset unavailable");
    }

    public DemoResetUnavailableException(Throwable cause) {
        super("Demo reset unavailable", cause);
    }
}
