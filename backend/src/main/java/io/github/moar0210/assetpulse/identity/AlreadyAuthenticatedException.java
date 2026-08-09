package io.github.moar0210.assetpulse.identity;

public class AlreadyAuthenticatedException extends RuntimeException {

    public AlreadyAuthenticatedException() {
        super("The current session is already authenticated");
    }
}
